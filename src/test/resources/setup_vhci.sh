#!/usr/bin/env bash
set -euo pipefail

sudo modprobe hci_vhci

sudo systemctl start bluetooth

sleep 1

HCI_INDEX=$(hciconfig | grep -oE '^hci[0-9]+' | head -n 1 || echo "hci0")

echo "Found virtual adapter: ${HCI_INDEX}"

sudo btmgmt -i "${HCI_INDEX}" power off
sudo btmgmt -i "${HCI_INDEX}" le on
sudo btmgmt -i "${HCI_INDEX}" connectable on
sudo btmgmt -i "${HCI_INDEX}" bondable off
sudo btmgmt -i "${HCI_INDEX}" name "test-device"

sudo btmgmt -i "${HCI_INDEX}" advertising on

sudo btmgmt -i "${HCI_INDEX}" power on

echo "Virtual BLE Peripheral 'test-device' is now advertising on ${HCI_INDEX}!"
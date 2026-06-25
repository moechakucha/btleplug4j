# btleplug4j

Java bindings for the Rust library [`btleplug`](https://github.com/deviceplug/btleplug).

## Build

To build the project you'll need:

- JDK 22 and upwards (for the FFM API)
- `jextract` matching the JDK version
- Rust
- Zig (for cross-compilation for Linux and macOS)
- MinGW (for cross-compilation for Windows)
- `cargo-zigbuild` and `cargo-xwin`
- `libdbus` development files and `bluez` when on Linux
- macOS SDK (for cross-compilation targeting macOS)

It's suggested to build this on a Linux host machine.

After you've added the relevant targets via `rustup` (check `build.gradle.kts` for target triples), to build the project
simply run:

```shell
./gradlew build
```


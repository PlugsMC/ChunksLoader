# ChunksLoader

This project contains a Bukkit/Spigot chunk loader plugin targeting Minecraft
releases up to **1.21.9**. The build is parametrised so you can generate jars
for every compatible version and ship them as release assets.

## Building

```sh
./gradlew build
```

> **Note:** The Gradle wrapper jar is stored in a Base64-encoded form at
> `gradle/wrapper/gradle-wrapper.jar.base64`. Running `./gradlew` restores the
> jar automatically. If you prefer to invoke Gradle directly, decode the file
> yourself with `base64 -d gradle/wrapper/gradle-wrapper.jar.base64 >
> gradle/wrapper/gradle-wrapper.jar` before running any tasks.

The command above generates a shaded jar named using the pattern
`ChunksLoader-<minecraft-version>-<release-tag>.jar` and places it in both
`build/libs/` and the repository-level `assets/` directory. By default the
Minecraft version is set to **1.21.9**.

To produce jars for every supported Minecraft version in one go, use the helper
script:

```sh
./scripts/build-all-assets.sh
```

The script reads the supported Minecraft versions from
`supported-versions.txt`, recompiles the plugin for each entry, and ensures that
every resulting jar is copied into the `assets/` directory using the
`ChunksLoader-<minecraft-version>-<release-tag>.jar` naming scheme so they
can be uploaded directly to a release.

## Configuration

The plugin ships with a `config.yml` that lets you change the loader radius and
other behaviour. Adjust the settings to fit your server's needs before
redeploying the plugin.

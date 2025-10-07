# ChunksLoader

This project contains a Bukkit/Spigot chunk loader plugin targeting Minecraft
releases up to **1.21.9**. The build is parametrised so you can generate jars
for every compatible version and ship them as release assets.

## Building

```sh
mvn -DskipTests package
```

The command above generates a shaded jar named using the pattern
`ChunksLoader-<minecraft-version>-v<plugin-version>.jar` and places it in both
`target/` and the repository-level `assets/` directory. By default the Minecraft
version is set to **1.21.9**.

To produce jars for every supported Minecraft version in one go, use the helper
script:

```sh
./scripts/build-all-assets.sh
```

## Configuration

The plugin ships with a `config.yml` that lets you change the loader radius and
other behaviour. Adjust the settings to fit your server's needs before
redeploying the plugin.

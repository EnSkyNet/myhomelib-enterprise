# MyHomeLib Plugin SDK samples

This reactor module is a buildable reference for MHL-503. It contains three `PluginEntrypoint` examples and a
`META-INF/services` descriptor. `SamplePluginsContractTest` discovers the samples through `ServiceLoader` and validates
all of them with the public `PluginTestHarness`.

The module is reference/developer material; it is not wired into the MyHomeLib desktop runtime.

See `../docs/plugin-sdk/README.md` for the authoring flow and security notes.

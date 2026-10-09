# MyHomeLib Plugin SDK samples

`myhomelib-plugin-samples` — buildable reference module для MHL-503. Він містить три `PluginEntrypoint` examples і `META-INF/services` descriptor. `SamplePluginsContractTest` знаходить samples через `ServiceLoader` та перевіряє їх public `PluginTestHarness`.

Модуль є developer/reference material і навмисно не входить у desktop runtime composition.

Основний authoring/security contract: [README.md](README.md). Compatibility policy: [COMPATIBILITY.md](COMPATIBILITY.md).

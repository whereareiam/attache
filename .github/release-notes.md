---
version: 1.4.0
---

# Attache 1.4.0 - Library transformers

`LibraryManager#registerLibraryTransformer` lets an application change every library jar after Attache downloaded and relocated it and before it is loaded, for example to rewrite its bytecode for an older Java runtime. Attache keeps the transformed jars in its save directory, keyed by the transformer's identifier and version, so a restart reuses them and a new transformer version transforms them again.

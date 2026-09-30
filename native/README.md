# native/

JNI hot loops only — nothing else (§15). This module exists for the rare
case where a measured profile shows a Kotlin/JVM hot path (e.g. a codec
inner loop) is the bottleneck on Class L hardware. It must not become a
dumping ground for logic that has a perfectly good Kotlin home in another
module.

**Status: empty.** No hot path has been profiled yet (there is nothing to
profile — Gate 1 ships interfaces and two small pure-Kotlin implementations,
none of which have been measured as CPU-bound). Do not add native code here
speculatively; add it only with a BENCHMARKS.md entry showing the Kotlin
version was measured and found too slow on the Class L reference device.

## Layout (once populated)
- `cpp/` — C/C++ sources
- `jni/` — JNI bridge headers/glue
- `CMakeLists.txt` — build config, wired into a consuming module's
  `externalNativeBuild` block when the first hot loop actually lands here

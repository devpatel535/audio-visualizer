// Shared helpers for the two Android script plugins.
//
// AGP's extension is configured through dynamic property access rather than
// through its Kotlin types, and that is a deliberate choice with a history:
//
//  1. Referencing an AGP type from a module's own build script fails to
//     compile when AGP is absent, because the Kotlin DSL type-checks a whole
//     script even where a branch will never run. Desktop and web builds would
//     then require the Android SDK and Google's Maven.
//
//  2. Moving the typed configuration into a script applied with
//     `apply(from = ...)` does not help: such a script does not inherit the
//     root buildscript classpath, so AGP's types are not on its *compile*
//     classpath either.
//
//  3. Giving the applied script its own buildscript block makes it compile,
//     but then AGP is loaded twice. The plugin is applied from the root
//     classloader while the type comes from the script's, and configuration
//     dies with:
//
//         class ...BaseAppModuleExtension_Decorated cannot be cast to
//         class com.android.build.api.dsl.ApplicationExtension
//
// Property get/set names no types at all, so none of the three applies. It
// gives up compile-time checking of AGP's DSL, which is a real cost; the
// property names below are stable AGP API and are exercised by CI's Android
// job on every push.

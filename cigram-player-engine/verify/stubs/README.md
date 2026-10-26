# Compile stubs (NOT the real libraries)

`verify/compile-glue.sh` type-checks the classes that touch ExoPlayer and the app's own code
(`CigramSubtitleOverlay`, `CigramPlayerBridge`, `CigramWatchNow`, `CigramCatalogSearchActivity`, ...).

The real ExoPlayer 2.19.1 jars could not be downloaded in the build environment (the network policy blocks
Google's Maven host), and the rest of the app is Sketchware project code that does not build outside Sketchware.
So this folder holds **hand-written signature stubs** of only the members those glue classes call:

* ExoPlayer 2.19.1 members (`ExoPlayer`, `C`, `Cue`, `SubtitleView`, `DefaultTrackSelector`) - written from the
  library's published API; they are not generated from the jar.
* The app's own members (`CigramContent.catalog`, `CigramStage1InitProvider.*`, `CigramDesign.*`, `AdsMedia.loadInto`) -
  copied from the signatures in the project source inside the two .swb files.

A successful run therefore proves the glue code is consistent with those signatures. It does **not** prove it
against the real ExoPlayer jars; that happens when the project is built in Sketchware.

# Local libraries

## Leia CNSDK connectors — one per device, neither in this repository

Both flavours here target Leia lightfield panels. They don't weave the two eyes into fixed
screen columns the way a bonded lenticular lens does: the diffractive backlight steers its
views, and where each view lands depends on where the viewer's face is. Only Leia's CNSDK
knows how to drive that, and the app uses the CNSDK already installed on the device rather
than bundling one — only a thin connector matching that device's CNSDK version goes in the
build.

The two devices run different CNSDK core lines, so each flavour needs its own connector:

    app/libs/leia-cnsdk.jar                                   (leiaLumepad, CNSDK 0.8.20)
    app/src/leiaLumepad/jniLibs/arm64-v8a/libleiaSDK-jni.so
    app/src/leiaLumepad/jniLibs/arm64-v8a/libleiaCore-loader.so
    app/src/leiaLumepad/assets/cnsdk.version

    app/libs/redmagic/leia-cnsdk.jar                          (leiaRedmagic, CNSDK 0.10.x)
    app/src/leiaRedmagic/jniLibs/arm64-v8a/libleiaSDK-jni.so
    app/src/leiaRedmagic/jniLibs/arm64-v8a/libleiaCore-loader.so
    app/src/leiaRedmagic/assets/cnsdk.version

None of that is in this repository. CNSDK is not published to Maven Central or any other
public repository — Leia licenses it to OEM and business partners under a signed agreement,
so it cannot be committed here or shipped in an APK that goes to anyone else. Build a
flavour for a device you own, and keep the result to yourself. To use it in anything you
intend to share, ask Leia for a licence at developers@leiainc.com.

`.gitignore` already excludes all of the above, so a clone of this repository stays clean.

See [docs/red-magic-3d-explorer.md](../../docs/red-magic-3d-explorer.md) for how the Red
Magic connector was obtained and why it can't simply be swapped with the Lume Pad 2's.

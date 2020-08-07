
[![Release](https://jitpack.io/v/crykn/gdx-gltf.svg)](https://jitpack.io/#crykn/gdx-gltf)

This is a fork of gdx-gltf, intended to be used as renderer in [ProjektGG](https://github.com/eskalon/ProjektGG). You can find the README of the original project [here](https://github.com/mgsx-dev/gdx-gltf).

### Changes in this fork

* Updated to libGDX 1.9.11
* Uses [nestable framebuffers](https://github.com/crykn/libgdx-screenmanager/wiki/Custom-FrameBuffer-implementation)
* Adds some stuff for convenience and ease of use:
   * `Scene#translateModel(...)`, `Scene#rotateModel(...)`
   * Disposing a SceneManager now disposes the Scenes and the Skybox as well
   * SceneSkybox can be given a custom ShaderProvider

# UV-2K

Android app that turns a front photo and a side photo of a face into a face **texture** in a chosen UV layout. It makes the texture only – no 3D model.

## Using it

Three steps, with one main button at the bottom right:

1. **Layout** – where each part of the face has to land in the image.
   - **Standard layout:** the face unwrapped flat in a square image (the layout FaceDroid heads use).
   - **My game's layout:** choose any one existing face texture exported from your game (PNG or JPG). The app finds the face in it and remembers where the eyes, nose, mouth and jaw sit; every texture you make then lines up the same way. If the face isn't found, or the mesh looks off, drag the yellow pins. This is saved, so you only do it once per game.
2. **Photos** – a front photo (needed) and a side photo (recommended). Faces are found automatically. Either side is fine: it is mirrored for the other side of the face. "Fix front pins" / "Fix side pins" are there if detection is off. A full side-on photo often isn't detected; in that case place 5 pins (nose tip, chin, top of head, back of head, ear).
3. **Texture** – preview, then **Save PNG** to `Download/UV-2K`.
   - **Around the face:** keep the reference texture, fill with the skin colour from the photo, or leave transparent (for layering in an image editor). The edge of the face fades softly into it.
   - **Size:** automatic (the reference texture's size, or 2048×2048 for the standard layout), 1024, 2048 or 4096.
   - **Side photo:** used or ignored.

## How it works

- MediaPipe Face Landmarker finds 468 mesh points in each image, including the reference texture.
- Each mesh triangle is copied from where it sits in the photo to where it sits in the layout.
- The front photo is used where the face looks at the camera and the side photo where it turns away, blended smoothly; the side photo's colour is matched to the front photo at the cheeks.
- Everything runs on the phone. Nothing is uploaded.

## Limits

- It covers the face mesh only: forehead to chin, cheek to cheek. Ears, scalp, neck and the back of the head are not made; with "Reference texture" they stay as they are in the reference.
- Photo lighting is baked in, so shadows and highlights in the photos end up in the texture. The skin tone isn't matched to the rest of the reference texture.
- Open eyes and mouths are copied as they are. If the game's head uses separate eyeball or teeth meshes, those areas may need touching up.
- The layout is only as good as the mesh fit on the reference texture, so check it once in step 1.

## Build

GitHub Actions builds the debug APK and runs the tests on every push (Actions → latest run → `UV-2K-apk`). The face model downloads automatically during the build. A fixed debug key is committed so new builds install over old ones.

`app/src/main/assets/face_topology.txt` is derived from MediaPipe's `canonical_face_model.obj` (Apache 2.0).

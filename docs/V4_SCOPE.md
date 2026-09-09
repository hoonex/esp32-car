# v4 scope freeze

This branch replaces the accumulated controller layering with one new Android surface and one firmware architecture derived from the original working v3.0 sketch.

Do not re-introduce legacy/premium cockpit composables into the runtime route. They may remain in source temporarily for history, but `MainActivity` must route only to `FreshCarScreen`.

Initial hardware flash remains Arduino IDE `.ino`. Normal future firmware updates are intended to use HTTP OTA only after physical v4 validation.

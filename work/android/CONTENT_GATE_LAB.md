# Content Gate Lab integration

The **Lab** tab is integrated into the app's bottom navigation. It is a developer diagnostics screen for measuring the existing content classifier; it does not replace the vision model and its controls do not change production swap/live/API policy.

## Features

- Multi-select local images and videos from Android's document picker.
- Download direct HTTP(S) image/video URLs into app-private cache (maximum 512 MiB each).
- Save an unchanged copy of selected media with Android's document picker.
- Run the existing native content scorer. Each run initializes the native pipeline under `PipeGuard`, checks the installed required model set, scores the item, releases the native pipeline, and invalidates the preview's warm-state assumption.
- Edit the **raw model-score cutoff** (`score > cutoff`; baseline `0.25`) to compare against the model's existing score margin. The score is a raw logit difference and is not bounded to `0…1`.
- Edit the **calibrated probability cutoff** (`0…1`) using a logistic mapping `p = 1 / (1 + exp(-(a*s + b)))`.
- Use the **lab response strength** (`0` = observe only, `1` = evaluate the lab rule) for diagnostic reporting only.
- Label items SFW / should-flag / unlabelled; fit an experimental one-dimensional logistic calibration, inspect false positives / false negatives / true positives / true negatives and export CSV + JSON rules/results.
- Video results retain sample and flagged-frame counts. Because the probability fit currently uses the worst sampled-frame score as the scalar video feature, compare image and video populations separately and validate on held-out samples.

## Native pipeline behavior

The scorer is a method on the existing native pipeline singleton. The lab consequently uses the same required runtime/model bundle as the app and reports missing or failed initialization as `ERROR`; it must not treat an inference failure as a pass. `PipeGuard` is acquired before `NativePipe.init`, scoring and release to avoid racing swaps or API requests. If another operation is using the pipeline, the lab reports a retryable busy error.

## Calibration notes

The raw classifier score is `out[0][0] - out[0][1]`, with the production baseline at `0.25`. This score is **not** a probability. The starting logistic coefficients (`a=4`, `b=-1`) are only a provisional visualization that maps a raw score of `0.25` to `0.5`; only a fit to representative labelled examples plus held-out validation can support probability claims. Fitting on the same labels used for metrics will produce in-sample metrics and may overstate performance.

Both raw and probability threshold controls are local to the lab. The production `ContentGate`, native threshold, model loading checks and swap enforcement are unchanged by this integration.

## Build

Use the existing [BUILDING.md](BUILDING.md) instructions. The full debug build requires JDK 17, Android SDK platform 35/build-tools 34.0.0, Gradle 8.13 and the project's native runtime inputs (`prebuiltNativeDir` containing matching `arm64-v8a` libraries, or the native build prerequisites). The source archive supplied for this integration does not include those large native runtime inputs. In environments that have the required SDKs and dependencies, build from `work/android` with the wrapper, for example:

```sh
./gradlew :app:assembleDebug -PprebuiltNativeDir=/absolute/path/to/native-libraries
```

No APK was built or validated in the current environment: the wrapper could not download Gradle 8.13 (`UnknownHostException: services.gradle.org`) and no Android SDK is configured here.

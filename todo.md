# Echidna TODO

## Gap analysis

### Native hooks
- [x] AAudio coverage is limited to data callback; no read/write hook path.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] OpenSL hook logs telemetry only; PCM is not processed.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] libc read fallback captures telemetry but does not run DSP.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [ ] Symbol discovery lacks signature-match fallback for vendor-only exports.  
  Spec: [4.1](spec.md#41-symbol-discovery--hooking-method)
- [x] Per-API level guards are not explicit around vendor symbol variants.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)
- [x] Hook failure telemetry does not surface which symbol path was chosen.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)

### DSP pipeline
- [x] Panic and auto-bypass triggers are not implemented in the native path.  
  Spec: [12](spec.md#12-safety--emergency-ux-native-risks)
- [ ] Hybrid worker mode lacks watchdog and xrun reporting integration.  
  Spec: [5](spec.md#5-dsp-pipeline--low-level-performance)
- [ ] Preset compatibility warnings are not surfaced to the app.  
  Spec: [17](spec.md#17-effect-parameter-reference-safe-ranges--warnings)
- [ ] Plugin signature failures are not reported to diagnostics.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

### Control service
- [ ] Binder push channel for profile sync is not implemented.  
  Spec: [3](spec.md#3-high-level-native-architecture-detailed)
- [ ] Per-app preset binding storage does not flow into whitelist snapshot.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [ ] Control service does not expose latency mode overrides per profile.  
  Spec: [8](spec.md#8-companion-app---native-features--ui-changes)

### Companion app
- [ ] Preset import/export/share flows are stubbed in UI.  
  Spec: [16.2](spec.md#162-preset-management)
- [ ] Effects chain editor is present but not wired to persistence.  
  Spec: [16.3](spec.md#163-effects-chain-editor-per-preset)
- [ ] Diagnostics charts do not show latency histograms or xrun counts.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [ ] Compatibility wizard lacks SELinux + HAL probe details.  
  Spec: [8](spec.md#8-companion-app---native-features--ui-changes)

### LSPosed shim
- [ ] Per-app whitelist UI and profile binding status are missing.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [x] Java AudioRecord hook does not enforce per-app policy fail-closed.  
  Spec: [Java/Kotlin](spec.md#3-high-level-native-architecture-detailed)

### Tooling, CI, docs
- [ ] Magisk packaging docs and release pipeline are incomplete.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [ ] CI does not run native hook smoke tests or DSP preset validation.  
  Spec: [11](spec.md#11-testing-plan-native-heavy)
- [ ] Doxygen or API reference is missing for public native headers.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

## TODO backlog

### Native hooks
- [x] Add AAudioStream_read/AAudioStream_write hook path with DSP routing.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Implement OpenSL buffer queue PCM conversion and DSP processing.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Add DSP processing to libc read fallback for /dev/snd captures.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Extend PltResolver with PLT/GOT scan and vendor symbol heuristics.  
  Spec: [4.1](spec.md#41-symbol-discovery--hooking-method)
- [x] Add hook selection telemetry (symbol path, lib, fallback reason).  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [x] Add API-level guards to hook managers for vendor ABI changes.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)

### DSP pipeline
- [x] Implement latency watchdog and auto-bypass for sustained overruns.  
  Spec: [12](spec.md#12-safety--emergency-ux-native-risks)
- [x] Add panic bypass toggle and timer to native engine state.  
  Spec: [12](spec.md#12-safety--emergency-ux-native-risks)
- [ ] Expose preset safety warnings in telemetry shared memory.  
  Spec: [17](spec.md#17-effect-parameter-reference-safe-ranges--warnings)
- [ ] Report plugin signature failures to diagnostics UI.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

### Control service
- [ ] Add binder method to push profile snapshots to native module.  
  Spec: [3](spec.md#3-high-level-native-architecture-detailed)
- [ ] Persist per-app preset bindings and include in profile snapshot.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [x] Add process-aware whitelist enforcement in control service API.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)

### Companion app
- [ ] Wire preset import/export/share flows to file picker and share sheet.  
  Spec: [16.2](spec.md#162-preset-management)
- [ ] Persist effects chain edits through ControlStateRepository.  
  Spec: [16.3](spec.md#163-effects-chain-editor-per-preset)
- [ ] Add latency histogram + CPU chart in Diagnostics screen.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [ ] Surface native engine status (installed/active/bypassed) in UI.  
  Spec: [8](spec.md#8-companion-app---native-features--ui-changes)
- [ ] Expand compatibility wizard with SELinux and HAL probes.  
  Spec: [8](spec.md#8-companion-app---native-features--ui-changes)

### LSPosed shim
- [ ] Implement per-app whitelist editor with default preset binding.  
  Spec: [16.4](spec.md#164-per-app-scope-unchanged-with-preset-binding)
- [ ] Enforce fail-closed policy in AudioRecord hook when app not allowed.  
  Spec: [Java/Kotlin](spec.md#3-high-level-native-architecture-detailed)

### Tooling, CI, docs
- [x] Add Magisk module packaging README and example release checklist.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [ ] Add CI job to run native dsp tests and hook smoke runs.  
  Spec: [11](spec.md#11-testing-plan-native-heavy)
- [ ] Add Doxygen config and baseline for native API headers.  
  Spec: [10](spec.md#10-developer-apis--extensibility)

## Work started
- [x] Harden profile sync parsing with whitelist validation and hooks flag.  
  Spec: [3](spec.md#3-high-level-native-architecture-detailed)
- [x] Fix Magisk module.prop templating to expand version fields.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [x] Process libc read fallback buffers through the DSP pipeline.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Process OpenSL buffer queue callbacks through DSP.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Hook AAudio read/write paths and route through DSP.  
  Spec: [4.2](spec.md#42-primary-hook-targets-priority-order)
- [x] Capture hook selection telemetry details for diagnostics.  
  Spec: [9](spec.md#9-diagnostics--instrumentation-native-specific)
- [x] Document Magisk packaging script and release checklist.  
  Spec: [7](spec.md#7-installation--deployment-steps-developer--user-flows)
- [x] Validate process names before updating the whitelist snapshot.  
  Spec: [4.3](spec.md#43-hooking-details--robustness)

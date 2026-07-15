#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/../.." && pwd)"
build_dir="${ECHIDNA_PERF_BUILD_DIR:-${repo_root}/build/audio-perf}"
output_dir="${ECHIDNA_PERF_OUTPUT_DIR:-${build_dir}/results}"
quick="${1:-}"
if [[ "${quick}" == "--quick" ]]; then
  warmups="${ECHIDNA_PERF_WARMUPS:-50}"
  iterations="${ECHIDNA_PERF_ITERATIONS:-300}"
else
  warmups="${ECHIDNA_PERF_WARMUPS:-200}"
  iterations="${ECHIDNA_PERF_ITERATIONS:-1200}"
fi
load_threads="${ECHIDNA_PERF_LOAD_THREADS:-1}"
safety_factor="${ECHIDNA_PERF_SAFETY_FACTOR:-4}"

configure=(
  -S "${script_dir}"
  -B "${build_dir}"
  -DCMAKE_BUILD_TYPE=Release
)
if command -v ninja >/dev/null 2>&1; then
  configure+=( -G Ninja )
fi

cmake "${configure[@]}"
cmake --build "${build_dir}" --config Release --parallel

executable="${build_dir}/audio_pipeline_benchmark"
for candidate in \
  "${build_dir}/audio_pipeline_benchmark" \
  "${build_dir}/Release/audio_pipeline_benchmark" \
  "${build_dir}/audio_pipeline_benchmark.exe" \
  "${build_dir}/Release/audio_pipeline_benchmark.exe"; do
  if [[ -x "${candidate}" ]]; then
    executable="${candidate}"
    break
  fi
done
if [[ ! -x "${executable}" ]]; then
  echo "Benchmark executable was not produced under ${build_dir}" >&2
  exit 74
fi

arguments=(
  --warmups "${warmups}"
  --iterations "${iterations}"
  --load-threads "${load_threads}"
  --safety-factor "${safety_factor}"
  --output-dir "${output_dir}"
)
if [[ "${quick}" == "--quick" ]]; then
  arguments=( --quick "${arguments[@]}" )
fi

benchmark_status=0
"${executable}" "${arguments[@]}" || benchmark_status=$?

if command -v python3 >/dev/null 2>&1; then
  python_bin=python3
elif command -v python >/dev/null 2>&1; then
  python_bin=python
else
  echo "Python 3 is required to validate the emitted benchmark report" >&2
  exit 69
fi

report_path="${output_dir}/audio_pipeline_results.json"
if [[ -f "${report_path}" ]]; then
  "${python_bin}" \
    "${script_dir}/validate_audio_pipeline_report.py" \
    "${script_dir}/audio_pipeline_result.schema.json" \
    "${report_path}"
elif [[ "${benchmark_status}" -eq 0 ]]; then
  echo "Benchmark exited successfully without emitting ${report_path}" >&2
  exit 74
fi
exit "${benchmark_status}"

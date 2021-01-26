#!/bin/bash --login

# Experiment configuration
BATCH_SIZE=256
BATCHES=50
REPEATS=5

# Activate the Conda environment
ROOT_DIR="$(readlink -f "$(dirname "${BASH_SOURCE[0]}")")"
. "$ROOT_DIR/conda/activate_conda_env.sh"

# Create a directory for experiment logs
EXP_LOG_DIR="$ROOT_DIR/logs/$(date +%Y%m%d-%H%M)_tensorflow-inference"
mkdir -p "$EXP_LOG_DIR"

# Record the experiment configuration
echo "batch_size: $BATCH_SIZE" > "$EXP_LOG_DIR/configuration"
echo "batch_count: $BATCHES" >> "$EXP_LOG_DIR/configuration"

for run in $(seq 1 $REPEATS); do
	for profile_type in none trace_per_batch trace; do
		echo "======================================================================"
		echo "STARTING TENSORFLOW INFERENCE EXPERIMENT WITH PROFILE $profile_type ($run/$REPEATS)"
		echo "======================================================================"
		echo

		RUN_LOG_DIR="$EXP_LOG_DIR/profile_$profile_type/$run"
		mkdir -p "$RUN_LOG_DIR"
		python src/tensorflow_inference_resnet50.py \
			--batch-size "$BATCH_SIZE" \
			--num-batches "$BATCHES" \
			--profile-type "$profile_type" \
			--log-dir "$RUN_LOG_DIR" "$@"

		echo
	done
done

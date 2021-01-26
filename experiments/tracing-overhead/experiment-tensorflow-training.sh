#!/bin/bash --login

# Experiment configuration
EPOCHS=5
REPEATS=5

# Activate the Conda environment
ROOT_DIR="$(readlink -f "$(dirname "${BASH_SOURCE[0]}")")"
. "$ROOT_DIR/conda/activate_conda_env.sh"

# Create a directory for experiment logs
EXP_LOG_DIR="$ROOT_DIR/logs/$(date +%Y%m%d-%H%M)_tensorflow-training"
mkdir -p "$EXP_LOG_DIR"

# Record the experiment configuration
echo "epochs: $EPOCHS" > "$EXP_LOG_DIR/configuration"

for run in $(seq 1 $REPEATS); do
	for profile_type in none trace trace_per_epoch; do
		echo "======================================================================="
		echo "STARTING TENSORFLOW MNIST EXPERIMENT WITH PROFILE $profile_type ($run/$REPEATS)"
		echo "======================================================================="
		echo

		RUN_LOG_DIR="$EXP_LOG_DIR/profile_$profile_type/$run"
		mkdir -p "$RUN_LOG_DIR"
		python src/tensorflow_training_mnist.py \
			--no-cuda \
			--epochs "$EPOCHS" \
			--profile-type "$profile_type" \
			--log-dir "$RUN_LOG_DIR" &

		# Record output from top for memory usage monitoring
		TF2_PID=$!
		while kill -0 $TF2_PID &>/dev/null; do
			top -b -d 0.5 -n 10 -p $TF2_PID >> "$RUN_LOG_DIR/top.txt"
		done

		echo
	done
done

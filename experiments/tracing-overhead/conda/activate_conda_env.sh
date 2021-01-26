#!/usr/bin/bash --login

# Resolve location of Conda env directory
ROOT_DIR="$(readlink -f "$(dirname "${BASH_SOURCE[0]}")")/.."
export ENV_DIR="$ROOT_DIR/env"

# Load external environment before activating Conda
. "$ROOT_DIR/conda/pre_activate.sh"

# Activate Conda
conda activate "$ENV_DIR"

#!/usr/bin/bash --login

# Resolve location of Conda env directory
ROOT_DIR="$(readlink -f "$(dirname "${BASH_SOURCE[0]}")")/.."
export ENV_DIR="$ROOT_DIR/env"

# Load external environment before activating Conda
. "$ROOT_DIR/conda/pre_activate.sh"

# Create the Conda environment
conda env create --prefix "$ENV_DIR" --file "$ROOT_DIR/conda/environment.yml"

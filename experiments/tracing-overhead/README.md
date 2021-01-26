# Experiment - Tracing Overhead

In this experiment we quantify the overhead of tracing in PyTorch and TensorFlow applications, using two example workloads --
training a CNN model on the MNIST dataset, and model inference using ResNet50.

## How to replicate this experiment?

Before running our experiments you must install the PyTorch and/or TensorFlow frameworks. We provide a Conda environment for consistency which can be created by running:

```
conda/create_conda_env.sh
```

After creating the Conda environment, you can reproduce any of our experiments using the `experiment-{platform}-{workload}.sh` scripts.
For example, to run inference workfload on TensorFlow with the experiment parameters we used, simply run:

```
./experiment-tensorflow-inference.sh
```

Results of the experiment are stored in the `logs/` subdirectory that will be created.

## Results of our experiments

The results we obtained in our environment can be found in `data/`. The following results are available:

- `inference_makespan.ssv`, `training_makespan.ssv`:
  Makespan of the inference and training workloads, respectively, for multiple configurations and repetitions
  Both workloads were executed on the PyTorch and TensorFlow frameworks.
  We ran each workload/framework combination without tracing (`none`), with tracing of the entire workload (`trace`),
  or with separately tracing each epoch/batch in the training/inference workload, resp. (`trace_per_epoch`/`trace_per_batch`).
- `pytorch_training_memory_use_*.txt`:
  Memory use of the PyTorch process while running the training workloads using three different levels of tracing
  (`none`, `trace`, `trace_per_epoch` as described in the previous item).
  Collected by running top in 0.5 second intervals and extracting the `RES` column ("Resident size").

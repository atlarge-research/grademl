# Resource Monitor

Lightweight resource monitor to accompany [Grade10](https://github.com/atlarge-research/grade10), a tool for automated bottleneck detection and performance issue identification.

## Getting Started

To use this resource monitor, you must compile it from source. First, obtain a copy of this repository by cloning it:

```bash
git clone https://github.com/thegeman/resource-monitor.git
cd resource-monitor
```

By default, the resource monitor is compiled with support for monitoring NVIDIA GPUs, which requires that the [CUDA Toolkit](https://developer.nvidia.com/cuda-toolkit) is installed.
If you wish to compile with the default configuration, run the following command:

```bash
make
```

If you do not have the CUDA toolkit installed or wish to compile without support for GPU monitoring, run the following command instead:

```bash
NO_CUDA=1 make
```

To start the resource monitor:

```bash
./bin/resource-monitor
```

Use the `--help` flag for more information about configuring the resource monitor.

## Additional Documentation

The output format of each monitoring module is detailed in [doc/file-formats.md](doc/file-formats.md).


#ifndef __RESMON_NVIDIA_H__
#define __RESMON_NVIDIA_H__

#include "monitor.h"

/**
 * NVML logger
 */
trace_file_t *init_nvml_logger(const char *output_directory, const char *hostname);

#endif



#include "nvidia.h"
#include "varint.h"

#include <nvml.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define NVML_CALL(fn_name, ...) \
	{ \
		nvmlReturn_t status = fn_name(__VA_ARGS__); \
		if (status != NVML_SUCCESS) { \
			fprintf(stderr, "Call to NVML function '%s' failed: %s\n", #fn_name, nvmlErrorString(status)); \
			exit(1); \
		} \
	}

typedef struct {
	unsigned int gpu_utilization;
	unsigned int memory_utilization;
	unsigned int tx_bytes;
	unsigned int rx_bytes;
} nvml_device_utilization;

typedef struct {
	nvmlUnit_t *unit_handles;
	unsigned int unit_count;
	nvmlDevice_t *device_handles;
	char **device_names;
	nvml_device_utilization *device_utilization;
	unsigned int device_count;
	bool is_initialized;
} nvml_data;

/**
 * Message writing logic
 */
typedef enum {
	DEVICE_LIST = 0,
	METRICS = 1
} nvidia_msgtype;

#define WRITE_BUFFER_SIZE (4 * 4096)
static char write_buffer[WRITE_BUFFER_SIZE];

static void write_device_list(FILE *output_file, nanosec_t timestamp, nvml_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("nvidia: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("nvidia: Writing message type: %u\n", DEVICE_LIST & 0xFF);
	*buffer_ptr = (char)DEVICE_LIST;
	buffer_ptr++;

	DEBUG_PRINT("nvidia: Writing num devices: %u\n", data->device_count);
	write_var_uint32_t(data->device_count, &buffer_ptr);

	for (unsigned int device_id = 0; device_id < data->device_count; device_id++) {
		DEBUG_PRINT("nvidia: Writing device name: %s\n", data->device_names[device_id]);
		int device_name_len = strlen(data->device_names[device_id]);
		if ((size_t)(end_of_buffer - buffer_ptr) < device_name_len + 1) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}
		memcpy(buffer_ptr, data->device_names[device_id], device_name_len + 1);
		buffer_ptr += device_name_len + 1;
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}

static void write_metrics(FILE *output_file, nanosec_t timestamp, nvml_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("nvidia: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("nvidia: Writing message type: %u\n", METRICS & 0xFF);
	*buffer_ptr = (char)METRICS;
	buffer_ptr++;

	DEBUG_PRINT("nvidia: Writing num devices: %u\n", data->device_count);
	write_var_uint32_t(data->device_count, &buffer_ptr);

	for (unsigned int device_id = 0; device_id < data->device_count; device_id++) {
		if ((size_t)(end_of_buffer - buffer_ptr) < 12) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}

		nvml_device_utilization *dev_util = &data->device_utilization[device_id];

		DEBUG_PRINT("nvidia: Writing gpu (%u), mem (%u), PCIe TX (%u), PCIe RX (%u) for device %u\n",
				dev_util->gpu_utilization, dev_util->memory_utilization,
				dev_util->tx_bytes, dev_util->rx_bytes, device_id);

		*buffer_ptr = (char)dev_util->gpu_utilization;
		buffer_ptr++;
		*buffer_ptr = (char)dev_util->memory_utilization;
		buffer_ptr++;
		write_var_uint32_t(dev_util->tx_bytes, &buffer_ptr);
		write_var_uint32_t(dev_util->rx_bytes, &buffer_ptr);
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}

/**
 * Initialization and shutdown of NVML interface
 */
static void initialize_nvml(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();
	nvml_data *nvd = trace_file->data;
	// Init
	nvmlReturn_t init_status = nvmlInit_v2();
	if (init_status == NVML_ERROR_DRIVER_NOT_LOADED) {
		nvd->is_initialized = false;
		return;
	} else if (init_status != NVML_SUCCESS) {
		fprintf(stderr, "Failed to initialize NVML: %s\n", nvmlErrorString(init_status));
		exit(1);
	}
	nvd->is_initialized = true;
	// Get the number of units
	NVML_CALL(nvmlUnitGetCount, &nvd->unit_count);
	DEBUG_PRINT("nvidia: Discovered %u units\n", nvd->unit_count);
	// Get a handle to each unit
	nvd->unit_handles = calloc(nvd->unit_count, sizeof(nvmlUnit_t));
	for (unsigned int unit_id = 0; unit_id < nvd->unit_count; unit_id++) {
		NVML_CALL(nvmlUnitGetHandleByIndex, unit_id, &nvd->unit_handles[unit_id]);
	}
	// Print details on each unit
	for (unsigned int unit_id = 0; unit_id < nvd->unit_count; unit_id++) {
		nvmlUnitInfo_t unit_info;
		NVML_CALL(nvmlUnitGetUnitInfo, nvd->unit_handles[unit_id], &unit_info);
		DEBUG_PRINT("nvidia: Details on unit %u\n", unit_id);
		DEBUG_PRINT("nvidia:   firmware version: %s\n", unit_info.firmwareVersion);
		DEBUG_PRINT("nvidia:   product id:       %s\n", unit_info.id);
		DEBUG_PRINT("nvidia:   product name:     %s\n", unit_info.name);
		DEBUG_PRINT("nvidia:   serial number:    %s\n", unit_info.serial);
	}
	// Get the number of devices
	NVML_CALL(nvmlDeviceGetCount_v2, &nvd->device_count);
	DEBUG_PRINT("nvidia: Discovered %u devices\n", nvd->device_count);
	// Get a handle to each device
	nvd->device_handles = calloc(nvd->device_count, sizeof(nvmlDevice_t));
	for (unsigned int device_id = 0; device_id < nvd->device_count; device_id++) {
		NVML_CALL(nvmlDeviceGetHandleByIndex_v2, device_id, &nvd->device_handles[device_id]);
	}
	// Get the name of each device
	nvd->device_names = calloc(nvd->device_count, sizeof(char *));
	for (unsigned int device_id = 0; device_id < nvd->device_count; device_id++) {
		nvd->device_names[device_id] = malloc(NVML_DEVICE_NAME_BUFFER_SIZE);
		NVML_CALL(nvmlDeviceGetName, nvd->device_handles[device_id], nvd->device_names[device_id], NVML_DEVICE_NAME_BUFFER_SIZE);
	}
	// Initialize data structures for storing device utilization
	nvd->device_utilization = calloc(nvd->device_count, sizeof(nvml_device_utilization));

	write_device_list(trace_file->output_file, sample_time, nvd);
}

static void shutdown_nvml(nvml_data *nvd) {
	// Shutdown
	nvmlShutdown();
}

/**
 * Log NVML data periodically
 */
static void log_nvml(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();
	nvml_data *nvd = trace_file->data;

	if (!nvd->is_initialized) {
		return;
	}

	// Iterate over devices
	for (unsigned int device_id = 0; device_id < nvd->device_count; device_id++) {
		unsigned int tx_bytes = 0, rx_bytes = 0;
		nvmlUtilization_t utilization;
		// Measure PCIe throughput and GPU utilization
		// NOTE: measuring PCIe throughput appears to have significant overhead, so disable for now
		//NVML_CALL(nvmlDeviceGetPcieThroughput, nvd->device_handles[device_id], NVML_PCIE_UTIL_TX_BYTES, &tx_bytes);
		//NVML_CALL(nvmlDeviceGetPcieThroughput, nvd->device_handles[device_id], NVML_PCIE_UTIL_RX_BYTES, &rx_bytes);
		NVML_CALL(nvmlDeviceGetUtilizationRates, nvd->device_handles[device_id], &utilization);
		// Store the utilization numbers
		nvml_device_utilization *dev_util = &nvd->device_utilization[device_id];
		dev_util->gpu_utilization = utilization.gpu;
		dev_util->memory_utilization = utilization.memory;
		dev_util->tx_bytes = tx_bytes;
		dev_util->rx_bytes = rx_bytes;
	}

	write_metrics(trace_file->output_file, sample_time, nvd);
}

/**
 * Parse module initialization and cleanup
 */
static void cleanup_nvml_logger(trace_file_t *trace_file) {
	shutdown_nvml((nvml_data *)trace_file->data);
	fclose(trace_file->output_file);
	free(((nvml_data *)trace_file->data)->unit_handles);
	free(((nvml_data *)trace_file->data)->device_handles);
	free(trace_file->data);
	free(trace_file);
}

trace_file_t *init_nvml_logger(const char *output_directory, const char *hostname) {
	char *output_filename = malloc(strlen(output_directory) + strlen("/nvidia-") + strlen(hostname) + 1);
	*output_filename = '\0';
	strcat(output_filename, output_directory);
	strcat(output_filename, "/nvidia-");
	strcat(output_filename, hostname);

	trace_file_t *trace_file = malloc(sizeof(trace_file_t));
	trace_file->parse_callback = log_nvml;
	trace_file->cleanup_callback = cleanup_nvml_logger;
	trace_file->source_file_name = NULL;
	trace_file->data = calloc(1, sizeof(nvml_data));
	trace_file->output_file = fopen(output_filename, "wb");

	free(output_filename);

	initialize_nvml(trace_file);

	return trace_file;
}


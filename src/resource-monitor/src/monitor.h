
#ifndef __MONITOR_H__
#define __MONITOR_H__

#include <stdbool.h>
#include <stdio.h>
#include <time.h>

/**
 * Debug helpers
 */
#ifdef DEBUG
	#define DEBUG_PRINT(...) printf(__VA_ARGS__)
#else
	#define DEBUG_PRINT(...) do {} while (0)
#endif


/**
 * Time representation
 */
typedef long long nanosec_t;
#define NANOSECONDS  (1LL)
#define MICROSECONDS (1000 * NANOSECONDS)
#define MILLISECONDS (1000 * MICROSECONDS)
#define SECONDS      (1000 * MILLISECONDS)

static inline nanosec_t get_time() {
	struct timespec current_time;
	clock_gettime(CLOCK_REALTIME, &current_time);
	return current_time.tv_sec * SECONDS + current_time.tv_nsec * NANOSECONDS;
}


/**
 * Monitor state
 */
typedef struct trace_file_t trace_file_t;
struct trace_file_t {
	void (*parse_callback)(trace_file_t *);
	void (*cleanup_callback)(trace_file_t *);
	const char *source_file_name;
	FILE *output_file;
	void *data;

	trace_file_t *next;
};

typedef struct {
	trace_file_t *trace_files;
	int trace_file_count;
} monitor_state_t;


/**
 * Program options
 */
typedef struct {
	const char *output_directory;
	nanosec_t monitor_period;
	const char *pid_file;
	bool enable_cpu_monitoring;
#ifdef CUDA
	bool enable_gpu_monitoring;
#endif
	bool enable_memory_monitoring;
	bool enable_network_monitoring;
	bool enable_disk_monitoring;
} monitor_options_t;

monitor_options_t parse_command_line(int argc, char **argv);

#endif


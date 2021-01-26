
#include "monitor.h"
#ifdef CUDA
#include "nvidia.h"
#endif
#include "procfs.h"

#include <fcntl.h>
#include <memory.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>


/**
 * Catch SIGINT and set a flag to stop the main monitoring loop.
 */
volatile bool interrupted = false;

void sigint_handler(int signum) {
	interrupted = true;
}

void setup_sigint_handler() {
	signal(SIGINT, sigint_handler);
}

/**
 * Sleep until a given moment in time.
 */
void sleep_until(nanosec_t wake_up_time) {
	nanosec_t current_time = get_time();
	if (current_time < wake_up_time) {
		nanosec_t sleep_time = wake_up_time - current_time;
		struct timespec sleep_timespec = {
			.tv_sec = sleep_time / SECONDS,
			.tv_nsec = (sleep_time % SECONDS) / NANOSECONDS
		};
		nanosleep(&sleep_timespec, NULL);
	}
}

/**
 * Management of the monitor_state_t.trace_files list.
 */
void init_trace_file(trace_file_t **trace_file_out, void (*parse_callback)(trace_file_t *), char *source_file_name) {
	trace_file_t *trace_file = malloc(sizeof(trace_file_t) + strlen(source_file_name) + 1);
	trace_file->parse_callback = parse_callback;
	trace_file->source_file_name = (char *)(((size_t)trace_file) + sizeof(trace_file_t));
	memcpy(source_file_name, trace_file->source_file_name, strlen(source_file_name) + 1);
}

void add_trace_file(monitor_state_t *monitor_state, trace_file_t *trace_file) {
	trace_file->next = monitor_state->trace_files;
	monitor_state->trace_files = trace_file;
	monitor_state->trace_file_count++;
}

/**
 * Management of the monitor state
 */
monitor_state_t init_state(monitor_options_t *opts, int argc, char **argv) {
	monitor_state_t res = { .trace_files = NULL, .trace_file_count = 0 };
	return res;
}

/**
 * Creation/destruction of PID file
 */
void create_pid_file(monitor_options_t *opts) {
	// Use the POSIX open function to atomically check that the PID
	// file does not yet exist and create it
	int pid_fd;
	if ((pid_fd = open(opts->pid_file, O_WRONLY | O_CREAT | O_EXCL,
			S_IRUSR | S_IWUSR)) == -1) {
		perror("Failed to create PID file\n");
		exit(1);
	}
	// Write this process's identifier to the PID file
	FILE *pid_file = fdopen(pid_fd, "w");
	fprintf(pid_file, "%d", getpid());
	fclose(pid_file);
}

void destroy_pid_file(monitor_options_t *opts) {
	unlink(opts->pid_file);
}

/**
 * Initialization
 */
void init_all_parsers(monitor_options_t *opts, monitor_state_t *state) {
	char hostname[256];
	gethostname(hostname, sizeof(hostname));
	hostname[255] = '\0';

	if (opts->enable_cpu_monitoring) add_trace_file(state, init_proc_stat_parser(opts->output_directory, hostname));
	if (opts->enable_memory_monitoring) add_trace_file(state, init_proc_meminfo_parser(opts->output_directory, hostname));
	if (opts->enable_network_monitoring) add_trace_file(state, init_proc_net_dev_parser(opts->output_directory, hostname));
	if (opts->enable_disk_monitoring) add_trace_file(state, init_proc_diskstats_parser(opts->output_directory, hostname));
#ifdef CUDA
	if (opts->enable_gpu_monitoring) add_trace_file(state, init_nvml_logger(opts->output_directory, hostname));
#endif
}


int main(int argc, char **argv) {
	setup_sigint_handler();
	monitor_options_t opts = parse_command_line(argc, argv);
	monitor_state_t state = init_state(&opts, argc, argv);
	create_pid_file(&opts);

	init_all_parsers(&opts, &state);

	nanosec_t last_update_time;
	while (!interrupted) {
		last_update_time = get_time();
		DEBUG_PRINT("Monitoring at t=%llu\n", last_update_time);

		for (trace_file_t *trace_file = state.trace_files; trace_file != NULL; trace_file = trace_file->next) {
			trace_file->parse_callback(trace_file);
		}

		sleep_until(last_update_time + opts.monitor_period);
	}

	printf("Received SIGINT, flushing output files and shutting down");
	destroy_pid_file(&opts);
	for (trace_file_t *trace_file = state.trace_files; trace_file != NULL; trace_file = trace_file->next) {
		trace_file->cleanup_callback(trace_file);
	}
}

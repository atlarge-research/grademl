
SOURCES = src/main.c src/options.c src/proc_stat.c src/proc_net_dev.c src/proc_diskstats.c src/proc_meminfo.c
C_OPTS = -std=gnu99

ifndef NO_CUDA
SOURCES += src/nvidia.c
C_OPTS += -lnvidia-ml -DCUDA=1
endif

all: bin/resource-monitor bin/resource-monitor-dbg

bin/resource-monitor: ${SOURCES} | bin
	gcc ${C_OPTS} -O3 -o $@ ${SOURCES}

bin/resource-monitor-dbg: ${SOURCES} | bin
	gcc ${C_OPTS} -g -DDEBUG=1 -o $@ ${SOURCES}

bin:
	mkdir -p $@

require(data.table)
require(ggplot2)
require(ggpubr)

output_directory <- "../"
data_directory <- "../.data/"
blocked_resource_filename <- "blocking-resource-usage.tsv"
consumable_resource_filename <- "consumable-resource-usage.tsv"
metric_list_filename <- "metric-list.tsv"
metric_type_list_filename <- "metric-type-list.tsv"
plot_filename <- "resource-usage.pdf"

NUM_POINTS <- 200
MAX_BLOCKING_RESOURCES <- 200

# Read metric (type) list
metric_list <- fread(paste0(data_directory, metric_list_filename))
metric_type_list <- fread(paste0(data_directory, metric_type_list_filename))
metric_list <- merge(metric_list, metric_type_list, by = "metric.type.id")[, .(
  metric.id = as.factor(metric.id),
  metric.path = as.factor(metric.path),
  metric.type.path = as.factor(metric.type.path),
  metric.class = as.factor(metric.class),
  capacity = as.double(capacity)
)]

# Read blocking resource usage and preprocess the data
blocking_usage <- fread(paste0(data_directory, blocked_resource_filename))
if (nrow(blocking_usage) > 0) {
  blocking_usage <- merge(blocking_usage, metric_list, by.x = "metric", by.y = "metric.id")[, .(
    metric.path = metric.path,
    start.time = start.time.slice / 1000.0,
    end.time = (end.time.slice.inclusive + 0.999) / 1000.0,
    is.blocked = is.blocked
  )][, .(
    metric.path = unlist(list(metric.path, metric.path)),
    time = c(start.time, end.time),
    is.blocked = c(is.blocked, is.blocked)
  )][order(metric.path, time)]
}

# Read consumable resource usage and preprocess the data
consumable_usage <- fread(paste0(data_directory, consumable_resource_filename))
if (nrow(consumable_usage) > 0) {
  approximate_usage <- function(dt) {
    if (nrow(dt) > NUM_POINTS) {
      min.start.time = min(dt$start.time)
      max.end.time = max(dt$end.time)
      duration = max.end.time - min.start.time
      grouped_data <- dt[, .(
        start.time,
        end.time,
        group.id = floor(((start.time + end.time) / 2 / duration) * NUM_POINTS),
        usage
      )][, .(
        time = (min(start.time) + max(end.time)) / 2,
        mean.usage = mean(usage),
        min.usage = min(usage),
        max.usage = max(usage)
      ), by = .(group.id)]
      return(grouped_data[, .(time, mean.usage, min.usage, max.usage)])
    } else {
      return(data.table(
        time = c(dt$start.time, dt$end.time),
        mean.usage = c(dt$usage, dt$usage),
        min.usage = c(dt$usage, dt$usage),
        max.usage = c(dt$usage, dt$usage)
      ))
    }
  }

  consumable_usage <- merge(consumable_usage, metric_list, by.x = "metric", by.y = "metric.id")[, .(
    metric.path = metric.path,
    metric.type.path = metric.type.path,
    start.time = time.slice / 1000.0,
    end.time = (time.slice + 0.999) / 1000.0,
    usage = usage,
    available.capacity = capacity.x,
    global.capacity = capacity.y
  )][, approximate_usage(.SD), by = .(metric.type.path, metric.path)][order(metric.type.path, metric.path, time)]
}

# Determine start and end time
if (nrow(blocking_usage) > 0) {
  start_time = min(min(blocking_usage$time), min(consumable_usage$time))
  end_time = max(max(blocking_usage$time), max(consumable_usage$time))
} else {
  start_time = min(consumable_usage$time)
  end_time = max(consumable_usage$time)
}

# Plot blocking resource usage
if (nrow(blocking_usage) > 0) {
  blocking_resource_count <- length(unique(blocking_usage$metric.path))
  if (blocking_resource_count <= MAX_BLOCKING_RESOURCES) {
    plot_blocking <- ggplot(blocking_usage) +
      geom_area(aes(x = time, y = is.blocked), fill = "gray50") +
      scale_x_continuous(limits = c(start_time, end_time)) +
      scale_y_discrete(expand = c(0, 0)) +
      facet_wrap(~ metric.path, nrow = blocking_resource_count)
  }
}

# Plot consumable resource usage
used_consumable_metrics <- unique(consumable_usage$metric.path)
used_consumable_metrics <- merge(data.table(metric.path = used_consumable_metrics), metric_list, by = "metric.path")[, .(
  metric.path,
  metric.type.path
)][order(metric.type.path, metric.path)]
consumable_resource_count <- length(used_consumable_metrics$metric.path)
consumable_blanks <- metric_list[metric.path %in% used_consumable_metrics$metric.path, .(
  metric.path = unlist(list(metric.path, metric.path)),
  time = start_time,
  usage = c(rep(0, .N), capacity)
)]
consumable_usage[, `:=`(metric.path = factor(metric.path, levels = used_consumable_metrics$metric.path))]
plot_consumable <- ggplot(consumable_usage) +
  geom_ribbon(aes(x = time, ymin = min.usage, ymax = max.usage), fill = "gray50") +
  geom_line(aes(x = time, y = mean.usage)) +
  geom_blank(data = consumable_blanks, aes(x = time, y = usage)) +
  scale_x_continuous(limits = c(start_time, end_time)) +
  facet_wrap(~ metric.path, scales = "free_y", nrow = consumable_resource_count)

if (nrow(blocking_usage) > 0 && blocking_resource_count <= MAX_BLOCKING_RESOURCES) {
  # Arrange the consumable and blocking usage in one plot
  p <- ggarrange(plot_consumable, plot_blocking, heights = c(4 * consumable_resource_count + 1.5, 1.4 * blocking_resource_count + 1.5), ncol = 1, nrow = 2, align = "v")

  # Save the plot
  ggsave(paste0(output_directory, plot_filename), width = 20, height = 4 * consumable_resource_count +
    1.4 * blocking_resource_count +
    3, units = "cm", limitsize = FALSE, p)
} else {
  # Save the plot
  ggsave(paste0(output_directory, plot_filename), width = 20, height = 4 * consumable_resource_count + 3,
    units = "cm", limitsize = FALSE, plot_consumable)
}

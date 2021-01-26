require(data.table)
require(ggplot2)
require(ggpubr)

output_directory <- "../"
data_directory <- "../.data/"
blocked_resource_filename <- "blocking-metrics.tsv"
consumable_resource_filename <- "consumable-metrics.tsv"
metric_list_filename <- "metric-list.tsv"
metric_type_list_filename <- "metric-type-list.tsv"
plot_filename <- "raw-metrics.pdf"
max_datapoints_per_plot <- 200

### START OF GENERATED SETTINGS ###
### :setting ns_per_timeslice   ###
### END OF GENERATED SETTINGS   ###

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
    start.time = start.time.slice * (ns_per_timeslice / 1000000000.0),
    end.time = (end.time.slice.inclusive + 0.999) * (ns_per_timeslice / 1000000000.0),
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
  consumable_usage <- merge(consumable_usage, metric_list, by.x = "metric", by.y = "metric.id")[, .(
    metric.path = metric.path,
    metric.type.path = metric.type.path,
    start.time = start.time.slice * (ns_per_timeslice / 1000000000.0),
    end.time = (end.time.slice.inclusive + 0.999) * (ns_per_timeslice / 1000000000.0),
    usage = observed.usage
  )][, .(
    metric.path = unlist(list(metric.path, metric.path)),
    time = c(start.time, end.time),
    usage = c(usage, usage)
  )][order(metric.path, time)]
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
  if (blocking_resource_count < 50) {
    plot_blocking <- ggplot(blocking_usage) +
      geom_area(aes(x = time, y = is.blocked), fill = "gray50") +
      scale_x_continuous(limits = c(start_time, end_time), expand = c(0, 0)) +
      scale_y_discrete(expand = c(0, 0)) +
      facet_wrap(~ metric.path, nrow = blocking_resource_count) +
      theme_bw()
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
max_datapoints_per_resource <- max(consumable_usage[, .(count = .N / 2), by = "metric.path"]$count)
if (max_datapoints_per_resource > max_datapoints_per_plot) {
  group_fn <- function(ru) {
    reduction_factor <- ceiling(nrow(ru) / 2 / max_datapoints_per_plot)
    # Split the resource usage data of each resource into at most "max_datapoints_per_plot" groups
    group_indices <- rep(1:max_datapoints_per_plot, each = reduction_factor * 2)[1:nrow(ru)]
    ru_with_group_indices <- ru[order(time)][, .(time, usage, group.id = group_indices)]
    # Take the average of each group and convert the input step function to a smooth(er) curve
    summary_ru <- ru_with_group_indices[, .(
      time = mean(min(time), max(time)),
      usage = mean(usage)
    ), by = "group.id"]
    return(summary_ru[, .(time, usage)])
  }
  grouped_consumable_usage <- consumable_usage[, group_fn(.SD), by = "metric.path"]
  
  plot_consumable <- ggplot(consumable_usage) +
    geom_line(data = grouped_consumable_usage, aes(x = time, y = usage)) +
    geom_line(aes(x = time, y = usage), color = "gray50", size = 0.1) +
    geom_blank(data = consumable_blanks, aes(x = time, y = usage)) +
    scale_x_continuous(limits = c(start_time, end_time), expand = c(0, 0)) +
    facet_wrap(~ metric.path, scales = "free_y", nrow = consumable_resource_count) +
    theme_bw()
} else {
  plot_consumable <- ggplot(consumable_usage) +
    geom_line(aes(x = time, y = usage)) +
    geom_blank(data = consumable_blanks, aes(x = time, y = usage)) +
    scale_x_continuous(limits = c(start_time, end_time), expand = c(0, 0)) +
    facet_wrap(~ metric.path, scales = "free_y", nrow = consumable_resource_count) +
    theme_bw()
}

if (nrow(blocking_usage) > 0 && blocking_resource_count < 50) {
  # Arrange the consumable and blocking usage in one plot
  p <- ggarrange(plot_consumable, plot_blocking, heights = c(4 * consumable_resource_count + 1.5, 1.4 * blocking_resource_count + 1.5), ncol = 1, nrow = 2, align = "v")
  
  # Save the plot
  ggsave(paste0(output_directory, plot_filename), width = 40, height = 4 * consumable_resource_count +
           1.4 * blocking_resource_count +
           3, units = "cm", limitsize = FALSE, p)
} else {
  # Save the plot
  ggsave(paste0(output_directory, plot_filename), width = 40, height = 4 * consumable_resource_count + 3,
         units = "cm", limitsize = FALSE, plot_consumable)
}

require(data.table)
require(ggplot2)
require(ggpubr)

output_directory <- "../"
data_directory <- "../.data/"
phase_list_filename <- "phase-list.tsv"
metric_type_list_filename <- "metric-type-list.tsv"
bottlenecks_filename <- "bottlenecks.tsv"
subphase_bottlenecks_filename <- "subphase-bottlenecks.tsv"
plot_filename <- "bottlenecks.pdf"

### START OF GENERATED SETTINGS ###
### :setting ns_per_timeslice   ###
### END OF GENERATED SETTINGS   ###

# Read phase list
phase_list <- fread(paste0(data_directory, phase_list_filename))
phase_list <- phase_list[, .(
  phase.id = as.factor(phase.id),
  phase.path = as.factor(phase.path)
)]

# Read metric type list
metric_type_list <- fread(paste0(data_directory, metric_type_list_filename))
metric_type_list <- metric_type_list[, .(
  metric.type.id = as.factor(metric.type.id),
  metric.type.path = as.factor(metric.type.path)
)]
num_all_metric_types <- length(unique(metric_type_list$metric.type.path))

# Read bottlenecks for the target phase and merge with metric list
bottlenecks <- fread(paste0(data_directory, bottlenecks_filename))
bottlenecks <- merge(bottlenecks, metric_type_list, by = "metric.type.id")[, .(
  metric.type.path,
  start.time = start.time.slice * (ns_per_timeslice / 1000000000.0),
  end.time = (end.time.slice.inclusive + 1) * (ns_per_timeslice / 1000000000.0),
  is.bottleneck = as.logical(is.bottleneck)
)]
# Read bottlenecks for subphases and merge with phase/metric list
subphase_bottlenecks <- fread(paste0(data_directory, subphase_bottlenecks_filename))
subphase_bottlenecks <- merge(merge(subphase_bottlenecks, phase_list, by = "phase.id"),
                              metric_type_list, by = "metric.type.id")[, .(
  phase.path,
  metric.type.path,
  start.time = start.time.slice * (ns_per_timeslice / 1000000000.0),
  end.time = (end.time.slice.inclusive + 1) * (ns_per_timeslice / 1000000000.0),
  is.bottleneck = as.logical(is.bottleneck)
)]

# Extract the start and end time of the target phase
min_time = min(bottlenecks$start.time)
max_time = max(bottlenecks$end.time)
# Extract the start and end time of each subphase
subphase_times = subphase_bottlenecks[, .(
  start.time = min(start.time),
  end.time = max(end.time)
), by = .(phase.path)]

# Keep only data points describing the presence of a bottleneck
bottlenecks <- bottlenecks[is.bottleneck == TRUE]
subphase_bottlenecks <- subphase_bottlenecks[is.bottleneck == TRUE]

# Assign a fixed color to each metric before checking which metrics are used
# This ensures consistency between plots with different (numbers of) metrics
metric_colours <- hcl(h = seq(15, 375, length = num_all_metric_types + 1), l = 65, c = 100)[1:num_all_metric_types]
metric_colours_shuffle_order <- c(seq(1, num_all_metric_types, 2), seq(2, num_all_metric_types, 2))
metric_colours <- metric_colours[metric_colours_shuffle_order]
names(metric_colours) <- metric_type_list$metric.type.path
# Get the set of metrics that should be depicted in this plot
selected_metrics <- unique(unlist(list(bottlenecks$metric.type.path, subphase_bottlenecks$metric.type.path)))
num_metrics = length(selected_metrics)
selected_metrics <- data.table(
  path = factor(selected_metrics, levels = selected_metrics),
  index = 1:num_metrics
)

# Set the order of metrics in the bottlenecks and subphase_bottlenecks datasets
bottlenecks <- merge(bottlenecks, selected_metrics, by.x = "metric.type.path", by.y = "path")[, .(
  metric.type.path = factor(metric.type.path, levels = selected_metrics$path),
  metric.index = index,
  start.time,
  end.time
)][order(metric.index, start.time)]
subphase_bottlenecks <- merge(subphase_bottlenecks, selected_metrics, by.x = "metric.type.path", by.y = "path")[, .(
  phase.path,
  metric.type.path = factor(metric.type.path, levels = selected_metrics$path),
  metric.index = index,
  start.time,
  end.time
)][order(phase.path, metric.index, start.time)]

# Get a list of periods of bottlenecks on any resource for the target phase
merge_periods <- function(dt) {
  periods <- dt[, .(start.time, end.time)][order(start.time)]
  periods[, max.end.time := c(1, head(cummax(end.time), -1))]
  periods[, is.new.period := 1 * (start.time > max.end.time)]
  periods[, period.id := cumsum(is.new.period)]
  return(
    periods[, .(
      start.time = min(start.time),
      end.time = max(end.time)
    ), by = .(period.id)][, .(
      start.time,
      end.time
    )]
  )
}
bottleneck_periods <- bottlenecks[, merge_periods(.SD)]
# Get a list of periods of bottlenecks on any resource for each subphase
subphase_bottleneck_periods <- subphase_bottlenecks[, merge_periods(.SD), by = .(phase.path)]

# Plot the bottleneck overview (top plot)
p_overview <- ggplot() +
  geom_rect(data = NULL, aes(xmin = min_time, xmax = max_time, ymin = 0, ymax = num_metrics),
            fill = "white", colour = NA)
if (nrow(bottlenecks) > 0) {
  p_overview <- p_overview +
    geom_rect(data = bottleneck_periods, aes(xmin = start.time, xmax = end.time, ymin = 0, ymax = num_metrics),
              fill = "grey80", colour = NA) +
    geom_rect(data = bottlenecks, aes(xmin = start.time, xmax = end.time,
                                      ymin = num_metrics - metric.index,
                                      ymax = num_metrics - metric.index + 1,
                                      fill = metric.type.path))
}
p_overview <- p_overview +
  geom_rect(data = NULL, aes(xmin = min_time, xmax = max_time, ymin = 0, ymax = num_metrics),
            fill = NA, colour = "grey20") +
  scale_x_continuous(limits = c(min_time, max_time), expand = c(0, 0)) +
  scale_y_discrete(limits = c(0, num_metrics), expand = c(0, 0)) +
  scale_fill_manual(values = metric_colours) +
  xlab("Time [s]") +
  ylab("") +
  ggtitle("Overview of resource bottlenecks") +
  theme(
    legend.position = "none"
  )

# Plot the bottlenecks per metric type
p_per_metric_type <- ggplot(data.table(metric.type.path = selected_metrics$path)) +
  geom_point(aes(x = min_time - 1, y = 0.5)) +
  geom_rect(data = NULL, aes(xmin = min_time, xmax = max_time, ymin = 0, ymax = 1),
            fill = "white", colour = NA)
if (nrow(bottlenecks) > 0) {
  p_per_metric_type <- p_per_metric_type +
    geom_rect(data = bottlenecks, aes(xmin = start.time, xmax = end.time, ymin = 0, ymax = 1, fill = metric.type.path))
}
p_per_metric_type <- p_per_metric_type +
  geom_rect(data = NULL, aes(xmin = min_time, xmax = max_time, ymin = 0, ymax = 1),
            fill = NA, colour = "grey20") +
  scale_x_continuous(limits = c(min_time, max_time), expand = c(0, 0)) +
  scale_y_discrete(limits = c(0, 1), expand = c(0, 0)) +
  scale_fill_manual(values = metric_colours) +
  xlab("Time [s]") +
  ylab("") +
  ggtitle("Bottlenecks per metric type") +
  theme(
    legend.position = "none"
  ) +
  facet_wrap(~ metric.type.path, nrow = num_metrics)
  
# Plot the bottleneck overview of each subphase
if (nrow(subphase_times) > 0) {
  p_subphase_overviews <- ggplot() +
    geom_rect(data = subphase_times, aes(xmin = start.time, xmax = end.time, ymin = 0, ymax = num_metrics),
              fill = "white", colour = NA) +
    geom_rect(data = subphase_bottleneck_periods, aes(xmin = start.time, xmax = end.time, ymin = 0, ymax = num_metrics),
              fill = "grey80", colour = NA) +
    geom_rect(data = subphase_bottlenecks, aes(xmin = start.time, xmax = end.time,
                                               ymin = num_metrics - metric.index,
                                               ymax = num_metrics - metric.index + 1,
                                               fill = metric.type.path)) +
    geom_rect(data = subphase_times, aes(xmin = start.time, xmax = end.time, ymin = 0, ymax = num_metrics),
              fill = NA, colour = "grey20") +
    scale_x_continuous(limits = c(min_time, max_time), expand = c(0, 0)) +
    scale_y_discrete(limits = c(0, num_metrics), expand = c(0, 0)) +
    scale_fill_manual(values = metric_colours) +
    xlab("Time [s]") +
    ylab("") +
    ggtitle("Overview of subphase bottlenecks") +
    theme(
      legend.position = "none"
    ) +
    facet_wrap(~ phase.path, nrow = nrow(subphase_times), ncol = 1)
}

# Assemble the final plot
if (nrow(subphase_times) > 0) {
  plot_1_height = 3
  plot_2_height = 2 + 1.5 * num_metrics
  plot_3_height = 2 + 1.5 * nrow(subphase_times)
  p <- ggarrange(p_overview, p_per_metric_type, p_subphase_overviews,
                 heights = c(plot_1_height, plot_2_height, plot_3_height), ncol = 1, nrow = 3, align = "v")
  ggsave(paste0(output_directory, plot_filename), width = 40,
         height = plot_1_height + plot_2_height + plot_3_height, units = "cm", limitsize = FALSE, p)
} else {
  plot_1_height = 3
  plot_2_height = 2 + 1.5 * num_metrics
  p <- ggarrange(p_overview, p_per_metric_type, heights = c(plot_1_height, plot_2_height),
                 ncol = 1, nrow = 2, align = "v")
  ggsave(paste0(output_directory, plot_filename), width = 40,
         height = plot_1_height + plot_2_height, units = "cm", limitsize = FALSE, p)
}

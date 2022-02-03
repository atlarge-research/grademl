require(data.table)
require(ggplot2)
require(ggpubr)

output_directory <- "../"
data_directory <- "../.data/"
phase_list_filename <- "simulated-phase-list.tsv"
phase_type_list_filename <- "phase-type-list.tsv"
plot_filename <- "simulated-subphases.pdf"

### START OF GENERATED SETTINGS ###
### :setting phase_type_filter  ###
### :setting depth_limit        ###
### END OF GENERATED SETTINGS   ###

# Sanity check: crash if settings don't exist
for (setting in c("phase_type_filter", "depth_limit")) {
  if (!exists(setting)) {
    stop(paste0("Setting ", setting, " is not defined"))
  }
}

# Read phase (type) list
phase_list <- fread(paste0(data_directory, phase_list_filename))
phase_type_list <- fread(paste0(data_directory, phase_type_list_filename))
phase_type_list$is.excluded <- sapply(phase_type_list$phase.type.id, function (t) t %in% phase_type_filter)
phase_list <- merge(phase_list, phase_type_list, by = "phase.type.id")[depth.x <= depth_limit & !is.excluded, .(
  phase.id = as.factor(phase.id),
  phase.path = as.factor(phase.path),
  phase.type.path = as.factor(phase.type.path),
  parent.phase.id = as.factor(parent.phase.id),
  depth = depth.x,
  start.time = simulated.start.time.slice / 1000.0,
  end.time = (simulated.end.time.slice.inclusive + 0.999) / 1000.0,
  canonical.index
)][, `:=`(
  parent.phase.id = factor(parent.phase.id, levels(phase.id))
)][order(-canonical.index)][, `:=`(
  order.num = 0:(.N - 1)
)]

# Format each phase's path to shorten every non-leaf component to one character
format_phase_label <- function(phase_path) {
  path_components <- unlist(strsplit(as.character(phase_path), "/", fixed = TRUE))
  if (length(path_components) < 3) {
    return(as.character(phase_path))
  }
  new_path_components = c("")
  for (i in 2:(length(path_components) -  1)) {
    new_path_components[i] <- substr(path_components[i], 1, 1)
  }
  new_path_components[length(path_components)] <- path_components[length(path_components)]
  return(paste(new_path_components, collapse = "/"))
}

# Translate each phase into a rectangle to be plotted
phase_count <- nrow(phase_list)
max_depth <- max(phase_list$depth)
min_rect_height <- 1
rect_height_increment <- 0.2
max_rect_height <- min_rect_height + max_depth * rect_height_increment
rect_margin <- max_rect_height * 0.25
rect_vertical_step <- max_rect_height + rect_margin
phase_rectangles <- phase_list[, .(
  xmin = start.time,
  xmax = end.time,
  ycenter = rect_vertical_step * (order.num + 0.5),
  height = max_rect_height - depth * rect_height_increment,
  ylabel = lapply(phase.path, format_phase_label),
  depth
)][, .(
  xmin,
  xmax,
  ymin = ycenter - height / 2,
  ymax = ycenter + height / 2,
  ycenter,
  ylabel,
  depth
)]

# Plot the phase rectangles
p <- ggplot(phase_rectangles) +
  geom_rect(aes(xmin = xmin, xmax = xmax, ymin = ymin, ymax = ymax, fill = factor(depth, levels = 0:max_depth)),
            colour = "black") +
  xlab("Time [s]") +
  ylab("Phases") +
  scale_x_continuous(expand = c(0, 0)) +
  scale_y_continuous(
    breaks = phase_rectangles$ycenter,
    labels = phase_rectangles$ylabel,
    minor_breaks = NULL,
    limits = c(0, phase_count * rect_vertical_step),
    expand = c(0, 0)
  ) +
  scale_fill_discrete(guide = FALSE) +
  theme_bw() +
  theme(
    panel.grid.major.y = element_blank(),
    axis.text.y = element_text(hjust = 0)
  )

# Save the plot to a file
ggsave(paste0(output_directory, plot_filename), width = 40, height = 1 * phase_count + 3,
       units = "cm", limitsize = FALSE, p)

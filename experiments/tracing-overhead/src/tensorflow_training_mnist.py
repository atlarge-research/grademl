import argparse
import os
import time

import tensorflow as tf
from tensorflow.python.profiler import profiler_v2 as profiler

VALID_PROFILER_TYPES=['none', 'trace', 'trace_per_epoch', 'tensorboard', 'tensorboard_full']

# Profiling and logging callbacks
class ProfilingCallbacks(tf.keras.callbacks.Callback):
  def __init__(self, log_dir, profiler_type):
    self.log_dir = log_dir
    self.profiler_type = profiler_type

    # Open a log file for epoch durations
    self.log_epoch_times = open(os.path.join(log_dir, 'epoch_times.tsv'), 'w+')
    print('epoch\ttraining.time\tprofiling.time', file=self.log_epoch_times)
    self.log_epoch_times.flush()

  def on_train_begin(self, logs=None):
    if self.profiler_type == 'trace':
      profiler.start(logdir=os.path.join(self.log_dir, 'trace'))

  def on_train_end(self, logs=None):
    if self.profiler_type == 'trace':
      profiler_start_time = time.time()
      profiler.stop()
      profiler_end_time = time.time()
      print('-1\t0\t{}'.format(profiler_end_time - profiler_start_time), file=self.log_epoch_times)
      self.log_epoch_times.flush()

  def on_epoch_begin(self, epoch, logs=None):
    self.epoch_start_time = time.time()
    if self.profiler_type == 'trace_per_epoch':
      profiler.start(logdir=os.path.join(self.log_dir, 'trace_epoch{}'.format(epoch)))

  def on_epoch_end(self, epoch, logs=None):
    epoch_end_time = time.time()
    if self.profiler_type == 'trace_per_epoch':
      profiler.stop()
      profiler_end_time = time.time()
    else:
      profiler_end_time = epoch_end_time
    print('{}\t{}\t{}'.format(epoch, epoch_end_time - self.epoch_start_time, profiler_end_time - epoch_end_time), file=self.log_epoch_times)
    self.log_epoch_times.flush()

class ProfilingCallbacksTensorBoard(tf.keras.callbacks.Callback):
  def __init__(self, log_dir, tensorboard_callbacks):
    self.log_dir = log_dir
    self.tensorboard_callbacks = tensorboard_callbacks

    # Open a log file for epoch durations
    self.log_epoch_times = open(os.path.join(log_dir, 'epoch_times.tsv'), 'w+')
    print('epoch\ttraining.time\tprofiling.time', file=self.log_epoch_times)
    self.log_epoch_times.flush()

  def set_model(self, model):
    self.tensorboard_callbacks.set_model(model)

  def on_train_begin(self, logs=None):
    self.tensorboard_callbacks.on_train_begin(logs)

  def on_train_end(self, logs=None):
    self.tensorboard_callbacks.on_train_end(logs)

  def on_test_begin(self, logs=None):
    self.tensorboard_callbacks.on_test_begin(logs)

  def on_test_end(self, logs=None):
    self.tensorboard_callbacks.on_test_end(logs)

  def on_train_batch_begin(self, batch, logs=None):
    self.tensorboard_callbacks.on_train_batch_begin(batch, logs)

  def on_train_batch_end(self, batch, logs=None):
    self.tensorboard_callbacks.on_train_batch_end(batch, logs)

  def on_epoch_begin(self, epoch, logs=None):
    self.epoch_start_time = time.time()
    self.tensorboard_callbacks.on_epoch_begin(epoch, logs)

  def on_epoch_end(self, epoch, logs=None):
    epoch_end_time = time.time()
    self.tensorboard_callbacks.on_epoch_end(epoch, logs)
    profiler_end_time = time.time()
    print('{}\t{}\t{}'.format(epoch, epoch_end_time - self.epoch_start_time, profiler_end_time - epoch_end_time), file=self.log_epoch_times)
    self.log_epoch_times.flush()

def mnist(log_dir, profiler_type, no_cuda, epochs, batch_size, validation_batch_size):
  # Set up CUDA
  use_cuda = not no_cuda and tf.config.list_physical_devices('GPU')
  if use_cuda:
    device = '/GPU:0'
  else:
    device = '/CPU:0'
    tf.config.set_visible_devices(tf.config.list_physical_devices('CPU'))
  print('Using device {}'.format(device))

  # Import the MNIST dataset
  (train_images, train_labels), (test_images, test_labels) = tf.keras.datasets.mnist.load_data()

  # Convert to floating point format and normalize
  train_images = (train_images / 255.0 - 0.1307) / 0.3081
  test_images = (test_images / 255.0 - 0.1307) / 0.3081
  # Add dimension: [batch_size, x, y] => [batch_size, x, y, depth]
  train_images = tf.expand_dims(train_images, -1)
  test_images = tf.expand_dims(test_images, -1)

  # Create the neural network
  model = tf.keras.Sequential([
    tf.keras.layers.Conv2D(32, (3, 3), activation='relu', input_shape=(28, 28, 1)),
    tf.keras.layers.Conv2D(64, (3, 3), activation='relu'),
    tf.keras.layers.MaxPooling2D((2, 2)),
    tf.keras.layers.Dropout(0.25),
    tf.keras.layers.Flatten(),
    tf.keras.layers.Dense(128, activation='relu'),
    tf.keras.layers.Dropout(0.5),
    tf.keras.layers.Dense(10, activation='softmax')
  ])

  # Compile the model
  model.compile(
      optimizer=tf.keras.optimizers.Adam(0.001),
      loss=tf.keras.losses.SparseCategoricalCrossentropy(),
      metrics=[tf.keras.metrics.SparseCategoricalAccuracy()]
  )

  # Train the model
  if profiler_type == 'tensorboard':
    callbacks=[ProfilingCallbacksTensorBoard(log_dir, tf.keras.callbacks.TensorBoard(
      log_dir=os.path.join(log_dir, 'tensorboard')
    ))]
  elif profiler_type == 'tensorboard_full':
    callbacks=[ProfilingCallbacksTensorBoard(log_dir, tf.keras.callbacks.TensorBoard(
      log_dir=os.path.join(log_dir, 'tensorboard'),
      write_graph=True,
      profile_batch='1, 1000000'
    ))]
  else:
    callbacks=[ProfilingCallbacks(log_dir, profiler_type)]

  model.fit(
      train_images,
      train_labels,
      batch_size=batch_size,
      shuffle=True,
      epochs=epochs,
      validation_data=(test_images, test_labels),
      validation_batch_size=validation_batch_size,
      callbacks=callbacks
  )

def main():
  # Training settings
  parser = argparse.ArgumentParser(description='PyTorch MNIST Example')
  parser.add_argument('--batch-size', type=int, default=64, metavar='N',
                      help='input batch size for training (default: 64)')
  parser.add_argument('--test-batch-size', type=int, default=1000, metavar='N',
                      help='input batch size for testing (default: 1000)')
  parser.add_argument('--epochs', type=int, default=20, metavar='N',
                      help='number of epochs to train (default: 20)')
  parser.add_argument('--no-cuda', action='store_true', default=False,
                      help='disables CUDA training')
  parser.add_argument('--profile-type', default='none', choices=VALID_PROFILER_TYPES,
                      help='type of profiling to perform (default: none)')
  parser.add_argument('--log-dir', required=True,
                      help='directory to store log files in')
  args = parser.parse_args()

  makespan_start = time.time()
  mnist(
      args.log_dir,
      args.profile_type,
      args.no_cuda,
      args.epochs,
      args.batch_size,
      args.test_batch_size
  )
  makespan_end = time.time()

  with open(os.path.join(args.log_dir, 'makespan'), 'w+') as f:
    print(str(makespan_end - makespan_start), file=f)

if __name__ == '__main__':
  main()

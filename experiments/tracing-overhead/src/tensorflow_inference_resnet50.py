import tensorflow as tf
from tensorflow.python.profiler import profiler_v2 as profiler

import argparse
import os
import time

# Training settings
parser = argparse.ArgumentParser(description='PyTorch ResNet50 Inference',
                                 formatter_class=argparse.ArgumentDefaultsHelpFormatter)
parser.add_argument('--batch-size', type=int, default=32,
                    help='input batch size for inference')
parser.add_argument('--num-batches', type=int, default=100,
                    help='input batch size for validation')
parser.add_argument('--no-cuda', action='store_true', default=False,
                    help='disables CUDA training')
parser.add_argument('--profile-type', default='none', choices=['none', 'trace', 'trace_per_batch'],
                    help='type of profiling to perform (default: none)')
parser.add_argument('--log-dir', required=True,
                    help='directory to store log files in')
args = parser.parse_args()

# Set up CUDA
args.cuda = not args.no_cuda and tf.config.list_physical_devices('GPU')
if args.cuda:
    device = '/GPU:0'
else:
    device = '/CPU:0'
    tf.config.set_visible_devices(tf.config.list_physical_devices('CPU'))
print('Using device {}'.format(device))

# Get a ResNet50 model with random weights
model = tf.keras.applications.ResNet50(weights = None)

# Benchmark inference with random inputs
log_batch_times = open(os.path.join(args.log_dir, 'batch_times.tsv'), 'w+')
print('Logging to {}'.format(log_batch_times))
print('batch\tinference.time\tprofiling.time', file=log_batch_times)

def do_batch(batch):
    if batch % 10 == 1 or batch == args.num_batches:
        print('Processing batch {} of {}...'.format(batch, args.num_batches))
    batch_data = tf.random.uniform(shape=[args.batch_size, 224, 224, 3])
    model(batch_data)

makespan_start_time = time.time()

if args.profile_type == 'none':
    for batch in range(1, 1 + args.num_batches):
        batch_start_time = time.time()
        do_batch(batch)
        batch_end_time = time.time()
        print('{}\t{}\t0'.format(batch, batch_end_time - batch_start_time), file=log_batch_times)
elif args.profile_type == 'trace':
    profiler.start(logdir=os.path.join(args.log_dir, 'trace'))
    for batch in range(1, 1 + args.num_batches):
        batch_start_time = time.time()
        do_batch(batch)
        batch_end_time = time.time()
        print('{}\t{}\t0'.format(batch, batch_end_time - batch_start_time), file=log_batch_times)
    profile_start_time = time.time()
    profiler.stop()
    profile_end_time = time.time()
    print('-1\t0\t{}'.format(profile_end_time - profile_start_time), file=log_batch_times)
else:
    for batch in range(1, 1 + args.num_batches):
        profiler.start(logdir=os.path.join(args.log_dir, 'trace_batch{}'.format(batch)))
        batch_start_time = time.time()
        do_batch(batch)
        batch_end_time = time.time()
        profile_start_time = time.time()
        profiler.stop()
        profile_end_time = time.time()
        print('{}\t{}\t{}'.format(batch, batch_end_time - batch_start_time, profile_end_time - profile_start_time), file=log_batch_times)

log_batch_times.flush()

makespan_end_time = time.time()

with open(os.path.join(args.log_dir, 'makespan'), 'w+') as f:
    print(makespan_end_time - makespan_start_time, file=f)


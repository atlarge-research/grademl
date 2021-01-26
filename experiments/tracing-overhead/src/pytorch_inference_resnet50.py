import torch
import torchvision.models as models
import torch.autograd.profiler as profiler
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

# Get a ResNet50 model with random weights
model = models.resnet50()

# Set up CUDA
args.cuda = not args.no_cuda and torch.cuda.is_available()
if args.cuda:
    device = 'cuda:0'
    torch.cuda.set_device(0)
    model.cuda()
else:
    device = 'cpu'

# Benchmark inference with random inputs
log_batch_times = open(os.path.join(args.log_dir, 'batch_times.tsv'), 'w+')
print('batch\tinference.time\tprofiling.time', file=log_batch_times)

def do_batch(batch):
    if batch % 10 == 1 or batch == args.num_batches:
        print('Processing batch {} of {}...'.format(batch, args.num_batches))
    batch_data = torch.randn(args.batch_size, 3, 224, 224).to(device)
    model(batch_data)

makespan_start_time = time.time()

if args.profile_type == 'none':
    for batch in range(args.num_batches):
        batch_start_time = time.time()
        do_batch(batch)
        batch_end_time = time.time()
        print('{}\t{}\t0'.format(batch, batch_end_time - batch_start_time), file=log_batch_times)
elif args.profile_type == 'trace':
    with profiler.profile(use_cuda=args.cuda) as prof:
        with profiler.record_function("model_inference"):
            for batch in range(args.num_batches):
                batch_start_time = time.time()
                do_batch(batch)
                batch_end_time = time.time()
                print('{}\t{}\t0'.format(batch, batch_end_time - batch_start_time), file=log_batch_times)
            profile_start_time = time.time()
    prof.export_chrome_trace(os.path.join(args.log_dir, 'trace.json'))
    profile_end_time = time.time()
    print('-1\t0\t{}'.format(profile_end_time - profile_start_time), file=log_batch_times)
else:
    for batch in range(args.num_batches):
        with profiler.profile(use_cuda=args.cuda) as prof:
            with profiler.record_function("model_inference"):
                batch_start_time = time.time()
                do_batch(batch)
                batch_end_time = time.time()
                profile_start_time = time.time()
        prof.export_chrome_trace(os.path.join(args.log_dir, 'trace_batch{}.json'.format(batch)))
        profile_end_time = time.time()
        print('{}\t{}\t{}'.format(batch, batch_end_time - batch_start_time, profile_end_time - profile_start_time), file=log_batch_times)

log_batch_times.flush()

makespan_end_time = time.time()

with open(os.path.join(args.log_dir, 'makespan'), 'w+') as f:
    print(makespan_end_time - makespan_start_time, file=f)


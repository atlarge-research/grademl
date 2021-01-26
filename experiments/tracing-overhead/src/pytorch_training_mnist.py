from __future__ import print_function
import argparse
import os
import time

if __name__ == '__main__':
    print('Importing PyTorch...')

import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.optim as optim
from torchvision import datasets, transforms
from torch.optim.lr_scheduler import StepLR

VALID_PROFILER_TYPES=['none', 'trace', 'trace_per_epoch', 'profile', 'profile_per_epoch']

# Derived from https://github.com/pytorch/examples/blob/master/mnist/main.py

class Net(nn.Module):
    def __init__(self):
        super(Net, self).__init__()
        self.conv1 = nn.Conv2d(1, 32, 3, 1)
        self.conv2 = nn.Conv2d(32, 64, 3, 1)
        self.dropout1 = nn.Dropout2d(0.25)
        self.dropout2 = nn.Dropout2d(0.5)
        self.fc1 = nn.Linear(9216, 128)
        self.fc2 = nn.Linear(128, 10)

    def forward(self, x):
        x = self.conv1(x)
        x = F.relu(x)
        x = self.conv2(x)
        x = F.relu(x)
        x = F.max_pool2d(x, 2)
        x = self.dropout1(x)
        x = torch.flatten(x, 1)
        x = self.fc1(x)
        x = F.relu(x)
        x = self.dropout2(x)
        x = self.fc2(x)
        output = F.log_softmax(x, dim=1)
        return output

# Copied from https://pytorch.org/tutorials/beginner/aws_distributed_training_tutorial.html
class AverageMeter(object):
    """Computes and stores the average and current value"""
    def __init__(self):
        self.reset()

    def reset(self):
        self.val = 0
        self.avg = 0
        self.sum = 0
        self.count = 0

    def update(self, val, n=1):
        self.val = val
        self.sum += val * n
        self.count += n
        self.avg = self.sum / self.count

def train(args, model, device, use_cuda, train_loader, loss_fn, optimizer, epoch):
    # Counters to track training performance
    data_time = AverageMeter()
    compute_time = AverageMeter()
    learn_time = AverageMeter()
    losses = AverageMeter()

    # Switch the model to train mode
    model.train()

    # Process batches
    last_time = time.time()
    for batch_idx, (data, target) in enumerate(train_loader):
        # Measure data loading time
        t = time.time()
        data_time.update(t - last_time)
        last_time = t

        # Create tensors for training, non-blocking if on CUDA
        #data = data.to(device, non_blocking=use_cuda)
        #target = target.to(device, non_blocking=use_cuda)
        data = data.to(device)
        target = target.to(device)

        optimizer.zero_grad()

        # Compute output and measure loss
        output = model(data)
        loss = loss_fn(output, target)
        losses.update(loss.item(), data.size(0))

        # Measure compute time
        t = time.time()
        compute_time.update(t - last_time)
        last_time = t

        # Compute gradients in a backward pass
        loss.backward()

        # Let the optimizer update model parameters
        optimizer.step()

        # Measure learning time
        t = time.time()
        learn_time.update(t - last_time)
        last_time = t

        # Log training progess
        if batch_idx % args.log_interval == 0:
            print('Train Epoch: [{}][{}/{}]\t'
                  'Time data {data_time.val:.3f} ({data_time.avg:.3f})\t'
                  'Time compute {compute_time.val:.3f} ({compute_time.avg:.3f})\t'
                  'Time learn {learn_time.val:.3f} ({learn_time.avg:.3f})\t'
                  'Loss: {losses.val:.4f} ({losses.avg:.4f})'.format(
                  epoch, batch_idx, len(train_loader), data_time=data_time,
                  compute_time=compute_time, learn_time=learn_time, losses=losses))

def test(args, model, device, use_cuda, test_loader, loss_fn, epoch):
    losses = AverageMeter()
    correct = 0

    # Switch the model to evaluate mode
    model.eval()
    with torch.no_grad():
        for batch_idx, (data, target) in enumerate(test_loader):
            # Map data and target tensors to the correct device, non-blocking if on CUDA
            #data = data.to(device, non_blocking=use_cuda)
            #target = target.to(device, non_blocking=use_cuda)
            data = data.to(device)
            target = target.to(device)

            # Compute output and measure loss
            output = model(data)
            loss = loss_fn(output, target)
            losses.update(loss.item(), data.size(0))

            # Count the number of correct predictions to determine the model's accuracy
            pred = output.argmax(dim=1, keepdim=True)  # get the index of the max log-probability
            correct += pred.eq(target.view_as(pred)).sum().item()

    print('Test set: Average loss: {:.4f}, Accuracy: {}/{} ({:.0f}%)\n'.format(
        losses.avg, correct, len(test_loader.dataset),
        100. * correct / len(test_loader.dataset)))

def handle_epoch(args, model, device, use_cuda, train_loader, test_loader, loss_fn, optimizer, epoch, scheduler):
    # Train the model
    print('\nBegin Training @ Epoch [{}]'.format(epoch + 1))
    train(args, model, device, use_cuda, train_loader, loss_fn, optimizer, epoch)

    # Test the current accuracy of the model
    print('Begin Validation @ Epoch [{}]'.format(epoch + 1))
    test(args, model, device, use_cuda, test_loader, loss_fn, epoch)

    # Adjust the learning rate
    scheduler.step()

def mnist(args):
    if not args.no_cuda and not torch.cuda.is_available():
        print('CUDA is not available. Either explicitly disable CUDA with --no-cuda, or run on a CUDA-enabled machine.')
        return
    use_cuda = not args.no_cuda

    torch.manual_seed(args.seed)

    device = torch.device('cuda' if use_cuda else 'cpu')

    # Initialize datasets and data loaders
    print('Setting up input data and data loaders...')
    print('=> Downloading input data (if needed)...')
    train_data = datasets.MNIST('/local/{}/sonet/datasets'.format(os.environ['USER']),
            train=True,
            download=True,
            transform=transforms.Compose([
                transforms.ToTensor(),
                transforms.Normalize((0.1307,), (0.3081,))
            ]))
    test_data = datasets.MNIST('/local/{}/sonet/datasets'.format(os.environ['USER']),
            train=False,
            transform=transforms.Compose([
                transforms.ToTensor(),
                transforms.Normalize((0.1307,), (0.3081,))
            ]))
    print('=> Initializing data loaders...')
    train_loader = torch.utils.data.DataLoader(train_data,
            batch_size=args.batch_size,
            shuffle=True,
            num_workers=1 if use_cuda else 0,
            pin_memory=use_cuda)
    test_loader = torch.utils.data.DataLoader(test_data,
            batch_size=args.batch_size,
            shuffle=True,
            num_workers=1 if use_cuda else 0,
            pin_memory=use_cuda)
    print('=> Done!')

    # Create the neural network
    device_ids = [device] if use_cuda else None
    output_device = device if use_cuda else None
    model = Net().to(device)

    # Create the loss function, optimizer, and learning rate schduler
    loss_fn = nn.NLLLoss().to(device)
    optimizer = optim.Adadelta(model.parameters(), lr=args.lr)
    scheduler = StepLR(optimizer, step_size=1, gamma=args.gamma)

    # Log file for epoch durations
    log_epoch_times = open(os.path.join(args.log_dir, 'epoch_times.tsv'), 'w+')
    print('epoch\ttraining.time\tprofile.parsing.time\tprofile.serialization.time', file=log_epoch_times)

    # Train the model
    if args.profile_type == 'none':
        for epoch in range(args.epochs):
            # Time the duration of the epoch (start)
            start_time = time.time()

            handle_epoch(args, model, device, use_cuda, train_loader, test_loader, loss_fn, optimizer, epoch, scheduler)

            # Time the duration of the epoch (end)
            end_time = time.time()
            print('{}\t{}\t0\t0'.format(epoch + 1, end_time - start_time), file=log_epoch_times)
            log_epoch_times.flush()
    elif 'epoch' in args.profile_type:
        for epoch in range(args.epochs):
            # Time the duration of the epoch (start)
            start_time = time.time()
            #print('== Training starting at {} =='.format(start_time))

            with torch.autograd.profiler.profile(use_cuda=use_cuda) as prof:
                handle_epoch(args, model, device, use_cuda, train_loader, test_loader, loss_fn, optimizer, epoch, scheduler)

                # Time the duration of the epoch (train split)
                train_end_time = time.time()
                #print('== Training ending / profile serialization starting at {} =='.format(train_end_time))


            # Time the duration of the epoch (serialization split)
            serialization_start_time = time.time()

            if 'profile' in args.profile_type:
                with open(os.path.join(args.log_dir, 'profile_epoch{}.txt'.format(epoch + 1)), 'w+') as log_file:
                    print(prof.key_averages().table(sort_by='cuda_time_total' if use_cuda else 'cpu_time_total', row_limit=1000000000), file=log_file)
            else:
                prof.export_chrome_trace(os.path.join(args.log_dir, 'trace_epoch{}.json'.format(epoch + 1)))

            # Time the duration of the epoch (end)
            end_time = time.time()
            #print('== Profile serialization ending at {} =='.format(end_time))
            print('{}\t{}\t{}\t{}'.format(epoch + 1, train_end_time - start_time, serialization_start_time - train_end_time, end_time - serialization_start_time), file=log_epoch_times)
            log_epoch_times.flush()
    else:
        with torch.autograd.profiler.profile(use_cuda=use_cuda) as prof:
            for epoch in range(args.epochs):
                # Time the duration of the epoch (start)
                start_time = time.time()

                handle_epoch(args, model, device, use_cuda, train_loader, test_loader, loss_fn, optimizer, epoch, scheduler)

                # Time the duration of the epoch (end)
                end_time = time.time()
                print('{}\t{}\t0\t0'.format(epoch + 1, end_time - start_time), file=log_epoch_times)
                log_epoch_times.flush()

            # Time the duration of the profiler parsing/serialization (start)
            parsing_start_time = time.time()


        # Time the duration of the profiler parsing/serialization (split)
        serialization_start_time = time.time()

        if 'profile' in args.profile_type:
            with open(os.path.join(args.log_dir, 'profile.txt'), 'w+') as log_file:
                print(prof.key_averages().table(sort_by='cuda_time_total' if use_cuda else 'cpu_time_total', row_limit=1000000000), file=log_file)
        else:
            prof.export_chrome_trace(os.path.join(args.log_dir, 'trace.json'))

        # Time the duration of the profiler serialization (end)
        end_time = time.time()
        print('-1\t0\t{}\t{}'.format(serialization_start_time - parsing_start_time, end_time - serialization_start_time), file=log_epoch_times)
        log_epoch_times.flush()


    if args.save_model:
        torch.save(model.state_dict(), 'mnist_cnn.pt')

def main():
    # Training settings
    parser = argparse.ArgumentParser(description='PyTorch MNIST Example')
    parser.add_argument('--batch-size', type=int, default=64, metavar='N',
                        help='input batch size for training (default: 64)')
    parser.add_argument('--test-batch-size', type=int, default=1000, metavar='N',
                        help='input batch size for testing (default: 1000)')
    parser.add_argument('--epochs', type=int, default=20, metavar='N',
                        help='number of epochs to train (default: 20)')
    parser.add_argument('--lr', type=float, default=1.0, metavar='LR',
                        help='learning rate (default: 1.0)')
    parser.add_argument('--gamma', type=float, default=0.7, metavar='M',
                        help='Learning rate step gamma (default: 0.7)')
    parser.add_argument('--no-cuda', action='store_true', default=False,
                        help='disables CUDA training')
    parser.add_argument('--seed', type=int, default=1, metavar='S',
                        help='random seed (default: 1)')
    parser.add_argument('--log-interval', type=int, default=10, metavar='N',
                        help='how many batches to wait before logging training status')
    parser.add_argument('--save-model', action='store_true', default=False,
                        help='For Saving the current Model')
    parser.add_argument('--profile-type', default='none', choices=VALID_PROFILER_TYPES,
                        help='type of profiling to perform (default: none)')
    parser.add_argument('--log-dir', required=True,
                        help='directory to store log files in')
    args = parser.parse_args()

    makespan_start = time.time()
    mnist(args)
    makespan_end = time.time()

    with open(os.path.join(args.log_dir, 'makespan'), 'w+') as f:
        print(str(makespan_end - makespan_start), file=f)

if __name__ == '__main__':
    main()

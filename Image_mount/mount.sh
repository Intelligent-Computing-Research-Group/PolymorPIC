#!/bin/sh

# Check for the correct number of input arguments
if [ $# -ne 3 ]; then
  echo "Usage: $0 <image-file> <partition-ID> <mount-point>"
  exit 1
fi

IMAGE_FILE=$1
PART_ID=$2
MOUNT_POINT=$3

# Check if the mount point directory exists
if [ ! -d "$MOUNT_POINT" ]; then
  echo "Error: Mount point $MOUNT_POINT does not exist."
  exit 1
fi

# Check if the mount point is already in use
if mountpoint -q "$MOUNT_POINT"; then
  echo "Error: Mount point $MOUNT_POINT is already mounted."
  exit 1
fi

# Run kpartx to create device maps and capture the output
OUTPUT=$(sudo kpartx -av "$IMAGE_FILE")
if [ $? -ne 0 ]; then
  echo "Error: kpartx command failed."
  exit 1
fi

echo "$OUTPUT"

# Extract the loop device number (e.g., from "loop0")
LOOP_DEVICE=$(echo "$OUTPUT" | grep -oP '(?<=loop)\d+' | head -1)

if [ -z "$LOOP_DEVICE" ]; then
  echo "Error: Failed to determine loop device."
  exit 1
fi

# Construct the partition path (e.g., /dev/mapper/loop0p1)
LOOP_PARTITION="/dev/mapper/loop${LOOP_DEVICE}p$PART_ID"

# Check if the specific partition device file was created successfully
if [ ! -e "$LOOP_PARTITION" ]; then
  echo "Error: Partition $LOOP_PARTITION does not exist."
  exit 1
fi

# Mount the partition to the specified directory
sudo mount "$LOOP_PARTITION" "$MOUNT_POINT"
if [ $? -ne 0 ]; then
  echo "Error: Failed to mount $LOOP_PARTITION at $MOUNT_POINT."
  exit 1
fi

echo "Successfully mounted $LOOP_PARTITION at $MOUNT_POINT."
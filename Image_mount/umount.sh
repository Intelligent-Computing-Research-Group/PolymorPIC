#!/bin/sh

# Check for the correct number of input arguments
if [ $# -ne 2 ]; then
  echo "Usage: $0 <image-file> <mount-point>"
  exit 1
fi

IMAGE_FILE=$1
MOUNT_POINT=$2

# Unmount the specified mount point
echo "Unmounting $MOUNT_POINT..."
sudo umount "$MOUNT_POINT"
if [ $? -ne 0 ]; then
  echo "Warning: Failed to unmount $MOUNT_POINT. It might not be mounted; continuing script..."
fi

# Remove the partition mappings for the image file
echo "Removing partition mappings for $IMAGE_FILE..."
sudo kpartx -d "$IMAGE_FILE"
if [ $? -ne 0 ]; then
  echo "Error: kpartx command failed."
  exit 1
fi

# Check for any remaining device mapper entries
echo "Checking device mappings..."
DMSETUP_OUTPUT=$(sudo dmsetup ls)
if echo "$DMSETUP_OUTPUT" | grep -q "No devices found"; then
  echo "No devices found, proceeding..."
else
  # Iterate through and remove found device mappings
  echo "Found device mappings, removing..."
  echo "$DMSETUP_OUTPUT" | while read -r line; do
    DEVICE=$(echo "$line" | awk '{print $1}')
    echo "Removing $DEVICE..."
    sudo dmsetup remove "$DEVICE"
    if [ $? -ne 0 ]; then
      echo "Error: Failed to remove $DEVICE."
      exit 1
    fi
  done
fi

# Identify which loop devices are associated with the image file
echo "Finding loop devices for $IMAGE_FILE..."
LOSETUP_OUTPUT=$(sudo losetup -l | grep "$IMAGE_FILE")

if [ -z "$LOSETUP_OUTPUT" ]; then
  echo "No loop devices found for $IMAGE_FILE."
else
  # Iterate through all identified loop devices and detach them
  echo "$LOSETUP_OUTPUT" | while read -r line; do
    LOOP_DEVICE=$(echo "$line" | awk '{print $1}')
    echo "Detaching loop device $LOOP_DEVICE..."
    sudo losetup -d "$LOOP_DEVICE"
    if [ $? -ne 0 ]; then
      echo "Error: Failed to detach $LOOP_DEVICE."
      exit 1
    fi
  done
fi

echo "Successfully cleaned up $IMAGE_FILE."
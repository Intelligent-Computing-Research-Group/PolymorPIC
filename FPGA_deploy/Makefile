# --- configuration ---

MAKEFLAGS += --trace
.SILENT:

ifneq (,$(wildcard workspace/config))
include workspace/config
endif

BOARD ?= zcu102
CONFIG ?= BigRocketPIC1024KB
HW_SERVER_ADDR ?= localhost:3121
JAVA_OPTIONS ?=
CFG_FORMAT ?= mcs

NO_JTAG ?= false
JTAG_VHDL_GEN =
ifeq ($(NO_JTAG), true)
    JTAG_VHDL_GEN += -no_jtag
endif

include board/$(BOARD)/Makefile.inc

# --- packages and repos ---

apt-install:
	sudo apt update
	sudo apt install default-jdk device-tree-compiler curl gawk \
	 libtinfo5 libmpc-dev libssl-dev gcc gcc-riscv64-linux-gnu 
ifeq ($(shell test -r /etc/os-release && . /etc/os-release && echo $$VERSION_CODENAME),jammy)
	sudo apt install python-is-python3
else
	sudo apt install python
endif

apt-install-qemi:
	sudo apt install qemu-system-misc opensbi u-boot-qemu qemu-utils

clean-sbt:
	rm -rf ~/.cache ~/.config/jgit ~/.ivy2 ~/.sbt

# --- download gcc, initrd and rootfs from github.com ---

workspace/gcc/tools.tar.gz:
	mkdir -p workspace/gcc

	if [ ! -d pre_download_tools ]; then \
		echo "Create pre_download_tools folder"; \
		mkdir pre_download_tools; \
	fi

	if [ ! -f pre_download_tools/tools.tar.gz ]; then \
		curl --netrc --location --header 'Accept: application/octet-stream' \
		  https://api.github.com/repos/eugene-tarassov/vivado-risc-v/releases/assets/18060315 \
		  -o pre_download_tools/tools.tar.gz.tmp; \
		mv pre_download_tools/tools.tar.gz.tmp pre_download_tools/tools.tar.gz; \
	fi
	cp pre_download_tools/tools.tar.gz $@

workspace/gcc/riscv: workspace/gcc/tools.tar.gz
	cd workspace/gcc && tar xzf tools.tar.gz
	touch $@


# --- build Linux kernel ---

.PHONY: linux
linux: linux-stable/arch/riscv/boot/Image

CROSS_COMPILE_LINUX = /usr/bin/riscv64-linux-gnu-

workspace/patch-linux-done: patches/linux.patch patches/fpga-axi-sdc.c patches/fpga-axi-eth.c patches/linux.config
	if [ -s patches/linux.patch ] ; then cd linux-stable && ( git apply -R --check ../patches/linux.patch 2>/dev/null || git apply ../patches/linux.patch ) ; fi
	cp -p patches/fpga-axi-eth.c  linux-stable/drivers/net/ethernet
	cp -p patches/fpga-axi-sdc.c  linux-stable/drivers/mmc/host
	cp -p patches/fpga-axi-uart.c linux-stable/drivers/tty/serial
	cp -p patches/linux.config linux-stable/.config
	mkdir -p workspace && touch workspace/patch-linux-done

linux-stable/arch/riscv/boot/Image: workspace/patch-linux-done
	make -C linux-stable ARCH=riscv CROSS_COMPILE=$(CROSS_COMPILE_LINUX) oldconfig
	make -C linux-stable ARCH=riscv CROSS_COMPILE=$(CROSS_COMPILE_LINUX) all


# --- build U-Boot ---

ROOTFS ?= SD
ROOTFS_URL ?= 192.168.0.100:/home/nfsroot/192.168.0.243

.PHONY: u-boot
u-boot: u-boot/u-boot-nodtb.bin

U_BOOT_SRC = $(wildcard patches/u-boot/*/*) \
  patches/u-boot/vivado_riscv64_defconfig \
  patches/u-boot/vivado_riscv64.h \
  patches/u-boot.patch

u-boot/configs/vivado_riscv64_defconfig: patches/u-boot/vivado_riscv64_defconfig Makefile
	cp patches/u-boot/vivado_riscv64_defconfig u-boot/configs
ifeq ($(ROOTFS),NFS)
	echo 'CONFIG_USE_BOOTARGS=y' >>u-boot/configs/vivado_riscv64_defconfig
	echo 'CONFIG_BOOTCOMMAND="booti $${kernel_addr_r} - $${fdt_addr}"' >>u-boot/configs/vivado_riscv64_defconfig
	echo 'CONFIG_BOOTARGS="root=/dev/nfs rootfstype=nfs rw nfsroot='$(ROOTFS_URL)',nolock,vers=4,tcp ip=dhcp earlycon console=ttyAU0,115200n8 locale.LANG=en_US.UTF-8"' >>u-boot/configs/vivado_riscv64_defconfig
else ifeq ($(JTAG_BOOT),1)
	echo 'CONFIG_USE_BOOTARGS=y' >>u-boot/configs/vivado_riscv64_defconfig
	echo 'CONFIG_BOOTCOMMAND="booti $${kernel_addr_r} $${ramdisk_addr_r} $${fdt_addr}"' >>u-boot/configs/vivado_riscv64_defconfig
	echo 'CONFIG_BOOTARGS="ro root=UUID=68d82fa1-1bb5-435f-a5e3-862176586eec earlycon initramfs.runsize=24M locale.LANG=en_US.UTF-8"' >>u-boot/configs/vivado_riscv64_defconfig
endif

workspace/patch-u-boot-done: u-boot/configs/vivado_riscv64_defconfig
	if [ -s patches/u-boot.patch ] ; then cd u-boot && ( git apply -R --check ../patches/u-boot.patch 2>/dev/null || git apply ../patches/u-boot.patch ) ; fi
	mkdir -p u-boot/board/vivado_riscv
	cp -p -r patches/u-boot/vivado_riscv64 u-boot/board/vivado_riscv
	cp -p patches/u-boot/vivado_riscv64.h u-boot/include/configs
	mkdir -p workspace && touch workspace/patch-u-boot-done

u-boot/u-boot-nodtb.bin: workspace/patch-u-boot-done $(U_BOOT_SRC)
	make -C u-boot CROSS_COMPILE=$(CROSS_COMPILE_LINUX) BOARD=vivado_riscv64 vivado_riscv64_config
	make -C u-boot \
	  BOARD=vivado_riscv64 \
	  CC=$(CROSS_COMPILE_LINUX)gcc \
	  CROSS_COMPILE=$(CROSS_COMPILE_LINUX) \
	  KCFLAGS='-O1 -gno-column-info' \
	  u-boot-nodtb.bin

u-boot-qemu:
	cd qemu/ && if [ ! -d u-boot ]; then git clone https://github.com/u-boot/u-boot.git; fi && cd u-boot && git checkout v2022.01
	cd qemu/u-boot && export CROSS_COMPILE=$(CROSS_COMPILE_LINUX) && make qemu-riscv64_smode_defconfig && make -j$(nproc)

# --- generate HDL ---

CONFIG_SCALA := $(subst rocket,Rocket,$(CONFIG))

# valid ROCKET_FREQ_MHZ values (MHz): 160 125 100 80 62.5 50 40 31.25 25 20
ROCKET_FREQ_MHZ ?= $(shell awk '$$3 != "" && "$(BOARD)" ~ $$1 && "$(CONFIG_SCALA)" ~ ("^" $$2 "$$") {print $$3; exit}' board/rocket-freq)

ROCKET_CLOCK_FREQ := $(shell echo - | awk '{printf("%.0f\n", $(ROCKET_FREQ_MHZ) * 1000000)}')
ROCKET_TIMEBASE_FREQ := $(shell echo - | awk '{printf("%.0f\n", $(ROCKET_FREQ_MHZ) * 10000)}')

MEMORY_SIZE ?= 0x40000000

ifneq ($(findstring Rocket32t,$(CONFIG_SCALA)),)
  CROSS_COMPILE_NO_OS_TOOLS = $(realpath workspace/gcc/riscv/bin)/riscv32-unknown-elf-
  CROSS_COMPILE_NO_OS_FLAGS = -march=rv32imac -mabi=ilp32 -DFF_FS_EXFAT=0
else ifneq ($(findstring Rocket32,$(CONFIG_SCALA)),)
  CROSS_COMPILE_NO_OS_TOOLS = $(realpath workspace/gcc/riscv/bin)/riscv32-unknown-elf-
  CROSS_COMPILE_NO_OS_FLAGS = -march=rv32imac -mabi=ilp32
else
  CROSS_COMPILE_NO_OS_TOOLS = $(realpath workspace/gcc/riscv/bin)/riscv64-unknown-elf-
  CROSS_COMPILE_NO_OS_FLAGS = -march=rv64imac -mabi=lp64
endif

ifeq ($(shell echo $$(($(MEMORY_SIZE) <= 0x80000000))),1)
  MEMORY_ADDR_RANGE32 = 0x80000000 $(MEMORY_SIZE)
  MEMORY_ADDR_RANGE64 = 0x0 0x80000000 0x0 $(MEMORY_SIZE)
else
  MEMORY_ADDR_RANGE32 = 0x80000000 0x80000000
  MEMORY_SIZE_CPU = ${shell cat workspace/$(CONFIG)/system.dts | sed -n 's/.*reg = <0x0 0x80000000 *\(0x[0-9A-Fa-f]*\) *0x\([0-9A-Fa-f]*\)>.*/\1\2/p'}
  MEMORY_SIZE_AWK = if (CPU < DDR) SIZE=CPU; else SIZE=DDR; printf "0x%x 0x%x", SIZE / 0x100000000, SIZE % 0x100000000
  MEMORY_ADDR_RANGE64 = 0x0 0x80000000 $(shell echo - | awk '{CPU=$(MEMORY_SIZE_CPU); DDR=$(MEMORY_SIZE); $(MEMORY_SIZE_AWK)}')
endif

SBT := java -Xmx12G -Xss8M $(JAVA_OPTIONS) -Dsbt.io.virtual=false -Dsbt.server.autostart=false -jar $(realpath sbt-launch.jar)

CHISEL_SRC_DIRS = \
  src/main \
  rocket-chip/src/main \
  rocket-chip/macros/src/main \
  rocket-chip/hardfloat/src/main \
  generators/gemmini/src/main \
  generators/riscv-boom/src/main \
  generators/sifive-cache/design/craft \
  generators/testchipip/src/main

CHISEL_SRC := $(foreach path, $(CHISEL_SRC_DIRS), $(shell test -d $(path) && find $(path) -iname "*.scala" -not -name ".*"))
FIRRTL = java -Xmx12G -Xss8M $(JAVA_OPTIONS) -cp `realpath target/scala-*/system.jar` firrtl.stage.FirrtlMain

# Generate default device tree - not including peripheral devices or board specific data
workspace/$(CONFIG)/system.dts: $(CHISEL_SRC) rocket-chip/bootrom/bootrom.img # workspace/patch-hdl-done
	mkdir -p workspace/$(CONFIG)/tmp
	cp rocket-chip/bootrom/bootrom.img workspace/bootrom.img
	$(SBT) "runMain freechips.rocketchip.diplomacy.Main --dir `realpath workspace/$(CONFIG)/tmp` --top Vivado.RocketSystem --config Vivado.$(CONFIG_SCALA)"
	mv workspace/$(CONFIG)/tmp/Vivado.$(CONFIG_SCALA).dts workspace/$(CONFIG)/system.dts
	rm -rf workspace/$(CONFIG)/tmp

# Generate board specific device tree, boot ROM and FIRRTL
workspace/$(CONFIG)/system-$(BOARD)/RocketSystem.fir: workspace/$(CONFIG)/system.dts $(wildcard bootrom/*) workspace/gcc/riscv
	mkdir -p workspace/$(CONFIG)/system-$(BOARD)
	cat workspace/$(CONFIG)/system.dts board/$(BOARD)/bootrom.dts >bootrom/system.dts
	sed -i "s#reg = <0x80000000 *0x.*>#reg = <$(MEMORY_ADDR_RANGE32)>#g" bootrom/system.dts
	sed -i "s#reg = <0x0 0x80000000 *0x.*>#reg = <$(MEMORY_ADDR_RANGE64)>#g" bootrom/system.dts
	sed -i "s#clock-frequency = <[0-9]*>#clock-frequency = <$(ROCKET_CLOCK_FREQ)>#g" bootrom/system.dts
	sed -i "s#timebase-frequency = <[0-9]*>#timebase-frequency = <$(ROCKET_TIMEBASE_FREQ)>#g" bootrom/system.dts
	if [ ! -z "$(ETHER_MAC)" ] ; then sed -i "s#local-mac-address = \[.*\]#local-mac-address = [$(ETHER_MAC)]#g" bootrom/system.dts ; fi
	if [ ! -z "$(ETHER_PHY)" ] ; then sed -i "s#phy-mode = \".*\"#phy-mode = \"$(ETHER_PHY)\"#g" bootrom/system.dts ; fi
	sed -i "/interrupts-extended = <&.* 65535>;/d" bootrom/system.dts
	make -C bootrom CROSS_COMPILE="$(CROSS_COMPILE_NO_OS_TOOLS)" CFLAGS="$(CROSS_COMPILE_NO_OS_FLAGS)" BOARD=$(BOARD) clean bootrom.img
	mv bootrom/system.dts workspace/$(CONFIG)/system-$(BOARD).dts
	mv bootrom/bootrom.img workspace/bootrom.img
	$(SBT) "runMain freechips.rocketchip.diplomacy.Main --dir `realpath workspace/$(CONFIG)/system-$(BOARD)` --top Vivado.RocketSystem --config Vivado.$(CONFIG_SCALA)"
	$(SBT) assembly
	rm workspace/bootrom.img

# Generate Rocket SoC HDL
workspace/$(CONFIG)/system-$(BOARD).v: workspace/$(CONFIG)/system-$(BOARD)/RocketSystem.fir
	$(FIRRTL) -i $< -o RocketSystem.v --compiler verilog \
	  --annotation-file workspace/$(CONFIG)/system-$(BOARD)/RocketSystem.anno.json \
	  --custom-transforms firrtl.passes.InlineInstances \
	  --target:fpga
	cp workspace/$(CONFIG)/system-$(BOARD)/RocketSystem.v workspace/$(CONFIG)/system-$(BOARD).v

# Generate Rocket SoC wrapper for Vivado
workspace/$(CONFIG)/rocket.vhdl: workspace/$(CONFIG)/system-$(BOARD).v
	mkdir -p vhdl-wrapper/bin
	javac -g -nowarn \
	  -sourcepath vhdl-wrapper/src -d vhdl-wrapper/bin \
	  -classpath vhdl-wrapper/antlr-4.8-complete.jar \
	  vhdl-wrapper/src/net/largest/riscv/vhdl/Main.java
	java -Xmx4G -Xss8M $(JAVA_OPTIONS) -cp \
	  vhdl-wrapper/src:vhdl-wrapper/bin:vhdl-wrapper/antlr-4.8-complete.jar \
	  net.largest.riscv.vhdl.Main -m $(CONFIG_SCALA) $(JTAG_VHDL_GEN) \
	  workspace/$(CONFIG)/system-$(BOARD).v >$@

# --- utility make targets to run SBT command line ---

.PHONY: sbt rocket-sbt

sbt:
	$(SBT)

rocket-sbt:
	cd rocket-chip && $(SBT)

# --- generate Vivado Project ---

.PHONY: vivado-tcl vivado-project vivado-gui bitstream

FPGA_FNM    ?= riscv_wrapper.bit

proj_name   = $(BOARD)-riscv
proj_path   = workspace/$(CONFIG)/vivado-$(proj_name)
proj_file   = $(proj_path)/$(proj_name).xpr
proj_time   = $(proj_path)/timestamp.txt
synthesis   = $(proj_path)/$(proj_name).runs/synth_1/riscv_wrapper.dcp
bitstream   = $(proj_path)/$(proj_name).runs/impl_1/$(FPGA_FNM)
cfgmem_file = workspace/$(CONFIG)/$(proj_name).$(CFG_FORMAT)
prm_file    = workspace/$(CONFIG)/$(proj_name).prm
vivado      = env XILINX_LOCAL_USER_DATA=no vivado -mode batch -nojournal -nolog -notrace -quiet

workspace/$(CONFIG)/system-$(BOARD).tcl: workspace/$(CONFIG)/rocket.vhdl workspace/$(CONFIG)/system-$(BOARD).v
	echo "set vivado_board_name $(BOARD)" >$@
	if [ "$(BOARD_PART)" != "" -a "$(BOARD_PART)" != "NONE" ] ; then echo "set vivado_board_part $(BOARD_PART)" >>$@ ; fi
	if [ "$(BOARD_CONFIG)" != "" ] ; then echo "set board_config $(BOARD_CONFIG)" >>$@ ; fi
	echo "set xilinx_part $(XILINX_PART)" >>$@
	echo "set rocket_module_name $(CONFIG_SCALA)" >>$@
	echo "set riscv_clock_frequency $(ROCKET_FREQ_MHZ)" >>$@
	echo "set memory_size $(MEMORY_SIZE)" >>$@
	echo 'cd [file dirname [file normalize [info script]]]' >>$@
	echo 'source ../../vivado.tcl' >>$@

vivado-tcl: workspace/$(CONFIG)/system-$(BOARD).tcl

$(proj_time): workspace/$(CONFIG)/system-$(BOARD).tcl
	if [ ! -e $(proj_path) ] ; then $(vivado) -source workspace/$(CONFIG)/system-$(BOARD).tcl || ( rm -rf $(proj_path) ; exit 1 ) ; fi
	date >$@

vivado-project: $(proj_time)



# # ---- package file -------
PACK_NAME ?= rocclab
WORKSPACE_NAME ?= workspace
pack:
	rm -rf ${WORKSPACE_NAME}/gcc
	mkdir -p temp/${PACK_NAME}
	cp -r board temp/${PACK_NAME}/
	cp -r uart temp/${PACK_NAME}/
	cp -r sdc temp/${PACK_NAME}/
	cp -r vhdl-wrapper temp/${PACK_NAME}/
	cp -r ethernet temp/${PACK_NAME}/
	cp -r ${WORKSPACE_NAME} temp/${PACK_NAME}/
	cp vivado.tcl temp/${PACK_NAME}/
	tar -cvf ${PACK_NAME}.tar -C temp/ ${PACK_NAME}
	mv ${PACK_NAME}.tar /root/
	rm -rf temp
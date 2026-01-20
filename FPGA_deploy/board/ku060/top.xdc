set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design] 
set_property BITSTREAM.GENERAL.COMPRESS true [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 50 [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4 [current_design]
set_property BITSTREAM.CONFIG.SPI_FALL_EDGE Yes [current_design]

set_property BITSTREAM.CONFIG.UNUSEDPIN pulldown [current_design]

# ddr clock
#create_clock -period 10.000 -name sysclk [get_ports ddrClk_clk_p]
set_property PACKAGE_PIN E22 [get_ports ddrClk_clk_p]
set_property IOSTANDARD DIFF_SSTL12 [get_ports ddrClk_clk_p]

# sys clock
#set_property -dict { PACKAGE_PIN AG11 IOSTANDARD LVCMOS33 } [get_ports sys_clk];
#create_clock -name sys_clk -period 10.00 [get_ports sys_clk]

# rest button
set_property -dict { PACKAGE_PIN AE25 IOSTANDARD LVCMOS18 } [get_ports { rst[0] }];

# led
set_property -dict { PACKAGE_PIN AP9  IOSTANDARD LVCMOS33 } [get_ports { sdio_reset }];     # led1

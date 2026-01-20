set_property PACKAGE_PIN AG13 [get_ports rst]
set_property IOSTANDARD LVCMOS33 [get_ports rst]

set_property PACKAGE_PIN G21 [get_ports {CLK_IN_D_clk_p}]
set_property PACKAGE_PIN F21 [get_ports {CLK_IN_D_clk_n}]
set_property IOSTANDARD LVDS_25 [get_ports {CLK_IN_D_clk_p}]
set_property IOSTANDARD LVDS_25 [get_ports {CLK_IN_D_clk_n}]

set_property CLOCK_DEDICATED_ROUTE BACKBONE [get_nets -of_objects [get_pins -hier clk_wiz_0/clk_in1]]
create_clock -period 8.000 -name sys_clock_bufds -waveform {0.000 4.000} [get_pins -hier clk_wiz_0/clk_in1]


set_property CLOCK_DEDICATED_ROUTE FALSE [get_nets riscv_i/util_ds_buf_0/U0/USE_IBUFDS.GEN_IBUFDS[0].IBUFDS_I/O]
set_property CLOCK_DEDICATED_ROUTE ANY_CMT_COLUMN [get_nets riscv_i/util_ds_buf_0/U0/IBUF_OUT_BUFG[0]]
# SDIO
set_property -dict { PACKAGE_PIN AE17  IOSTANDARD LVCMOS18  } [get_ports { sdio_clk }];
set_property -dict { PACKAGE_PIN AG16  IOSTANDARD LVCMOS18  } [get_ports { sdio_cmd }];
set_property -dict { PACKAGE_PIN AF18  IOSTANDARD LVCMOS18  } [get_ports { sdio_dat[0] }];
set_property -dict { PACKAGE_PIN AF17  IOSTANDARD LVCMOS18  } [get_ports { sdio_dat[1] }];
set_property -dict { PACKAGE_PIN AE15  IOSTANDARD LVCMOS18  } [get_ports { sdio_dat[2] }];
set_property -dict { PACKAGE_PIN AG17  IOSTANDARD LVCMOS18  } [get_ports { sdio_dat[3] }];
#set_property -dict { PACKAGE_PIN M21  IOSTANDARD LVCMOS33 } [get_ports { sdio_reset }];
set_property -dict { PACKAGE_PIN AE18  IOSTANDARD LVCMOS18 } [get_ports { sdio_cd }];
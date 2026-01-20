#-----------------------------------------------------------
#              Ethernet / RGMII                            -
#-----------------------------------------------------------

set_property -dict { PACKAGE_PIN G21  IOSTANDARD LVCMOS33 } [get_ports { eth_mdio_clock }];
set_property -dict { PACKAGE_PIN G22  IOSTANDARD LVCMOS33 } [get_ports { eth_mdio_data }];
set_property -dict { PACKAGE_PIN D21  IOSTANDARD LVCMOS33 } [get_ports { eth_mdio_int }];
set_property -dict { PACKAGE_PIN E21  IOSTANDARD LVCMOS33 } [get_ports { eth_mdio_reset }];
set_property -dict { PACKAGE_PIN C18  IOSTANDARD LVCMOS33 } [get_ports { rgmii_rxc }];
set_property -dict { PACKAGE_PIN C22  IOSTANDARD LVCMOS33 } [get_ports { rgmii_rx_ctl }];
set_property -dict { PACKAGE_PIN D20  IOSTANDARD LVCMOS33 } [get_ports { rgmii_rd[0] }];
set_property -dict { PACKAGE_PIN C20  IOSTANDARD LVCMOS33 } [get_ports { rgmii_rd[1] }];
set_property -dict { PACKAGE_PIN A18  IOSTANDARD LVCMOS33 } [get_ports { rgmii_rd[2] }];
set_property -dict { PACKAGE_PIN A19  IOSTANDARD LVCMOS33 } [get_ports { rgmii_rd[3] }];
set_property -dict { PACKAGE_PIN C19  IOSTANDARD LVCMOS33 } [get_ports { rgmii_txc }];
set_property -dict { PACKAGE_PIN B22  IOSTANDARD LVCMOS33 } [get_ports { rgmii_tx_ctl }];
set_property -dict { PACKAGE_PIN B20  IOSTANDARD LVCMOS33 } [get_ports { rgmii_td[0] }];
set_property -dict { PACKAGE_PIN A20  IOSTANDARD LVCMOS33 } [get_ports { rgmii_td[1] }];
set_property -dict { PACKAGE_PIN B21  IOSTANDARD LVCMOS33 } [get_ports { rgmii_td[2] }];
set_property -dict { PACKAGE_PIN A21  IOSTANDARD LVCMOS33 } [get_ports { rgmii_td[3] }];

create_clock -period 8.000 -name rgmii_rx_clk [get_ports rgmii_rxc]

# Nexys Video board uses RTL8211E-VB phy, TXDLY off, RXDLY off, 2.5V signaling, HR bank (ODELAY not available).
# Note: max (setup) is measured from prev clock edge, min (hold) - from current clock edge.
# Data valid period, relative to the current clock edge, is [max-4.0ns .. min].
# With RXDLY off, the center of data valid period is in the middle between clock edges.
# Changing of the constraints require changes of IDELAY_VALUE in ethernet-nesys-video.v.
set_input_delay -add_delay -clock rgmii_rx_clk -max 4.60 [get_ports { rgmii_rd* rgmii_rx_ctl }]
set_input_delay -add_delay -clock rgmii_rx_clk -min 3.40 [get_ports { rgmii_rd* rgmii_rx_ctl }]
set_input_delay -add_delay -clock rgmii_rx_clk -max 4.60 -clock_fall [get_ports { rgmii_rd* rgmii_rx_ctl }]
set_input_delay -add_delay -clock rgmii_rx_clk -min 3.40 -clock_fall [get_ports { rgmii_rd* rgmii_rx_ctl }]

# To see implemented RX timing, run from Vivado Tcl Console:
# report_timing -from [get_ports {rgmii_rd* rgmii_rx_ctl}] -rise_to rgmii_rx_clk -delay_type min_max -max_paths 10 -name rgmii_rx  -file rgmii_rx.txt

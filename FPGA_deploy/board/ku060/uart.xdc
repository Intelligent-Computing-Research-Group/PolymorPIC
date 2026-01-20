# UART
set_property -dict {PACKAGE_PIN AP13  IOSTANDARD LVCMOS33} [get_ports usb_uart_rxd]
set_property -dict {PACKAGE_PIN AN13 IOSTANDARD LVCMOS33} [get_ports usb_uart_txd]
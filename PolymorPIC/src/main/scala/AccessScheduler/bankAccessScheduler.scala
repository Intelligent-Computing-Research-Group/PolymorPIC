package PIC

import chisel3._ // VecInit

import chisel3.util._ // MuxCase
import freechips.rocketchip.tile._
import freechips.rocketchip.subsystem._  // for getting subsystembus parameter
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._ // LazyModule
import freechips.rocketchip.rocket._ // cache 接口
import freechips.rocketchip.util._


// requeset the BankArray read or write
class ReqPackage(sys_configs:Sys_Config) extends Bundle {
    val addr = UInt((sys_configs.accessBankFullAddr_sigLen).W)
    val optype = Bool()
    val dataWrittenToBank= UInt(64.W) 
}


class AccessBank_Arb(sys_configs:Sys_Config,core_configs:PolymorPIC_Kernal_Config)(implicit val p: Parameters) extends Module
{
    val mat_inner_offset=log2Ceil(core_configs.arrayNums*core_configs.wordlineNums)
    val client_num=sys_configs.bank_access_client_num
    val io=IO(new Bundle {
        // vec size=2 one for p2s, one for L auto load , one for accumulate
        val request=Vec(client_num, Flipped(Decoupled(new ReqPackage(sys_configs))))
        val dataReadFromBank=Output(UInt(64.W)) // 这是返回给clents的

        // Connected to banks, used to access mem directly
        val cache_enable = Output(Bool()) // 选subarrray
        val cache_write = Output(Bool())
        val cache_addr = Output(UInt((log2Ceil(sys_configs.bank_num)+log2Ceil(sys_configs.mat_per_bank)+mat_inner_offset).W))
        val cache_data_to_bank = Output(UInt(core_configs.bitlineNums.W))
        val cache_data_from_bank = Input(UInt(core_configs.bitlineNums.W))  // 这是返连接到banks的
        
    })

    // init
    io.cache_enable:=false.B
    io.cache_write:=false.B
    io.cache_addr:=0.U
    io.cache_data_to_bank:=0.U

    io.dataReadFromBank:=io.cache_data_from_bank

    val arbiter = Module(new RRArbiter(new ReqPackage(sys_configs), client_num))

    for(i<-0 until client_num)
    {
        arbiter.io.in(i)<>io.request(i)
    }

    // always ready for receiving a request
    arbiter.io.out.ready:=true.B

    // 表明接收到了一个请求
    when(arbiter.io.out.fire)
    {
        val optype=arbiter.io.out.bits.optype

        // Write instantly
        when(optype===AccessArrayType.WRITE)
        {
            io.cache_enable:=true.B
            io.cache_write:=true.B
            io.cache_addr:=arbiter.io.out.bits.addr
            io.cache_data_to_bank:=arbiter.io.out.bits.dataWrittenToBank
        }
        .otherwise  // Read to read_mem_res state
        {
            io.cache_enable:=true.B
            io.cache_write:=false.B
            io.cache_addr:=arbiter.io.out.bits.addr
        }
    }

}
package PIC


import freechips.rocketchip.tile._
import chisel3._
import chisel3.util._


object AccessArrayType
{
    val READ=true.B
    val WRITE=false.B
}

object DMA_read_type
{
    val NORMAL=0.U
    val DMA_P2S_R=1.U
    val DMA_P2S_L=2.U
}

object P2S_type
{
    val P2S_R=true.B
    val P2S_L=true.B
}

object PolymorPIC_Configs
{
    val sysCfg=Sys_Config()
    val _ISA_ = ISA(sysCfg)
    _ISA_.genHeaderFile()
}

object PIC_Switch
{
    val ALLOC=true.B
    val FREE=false.B
}

// Configuration under 1024KB LLC cache
// Each set has 16 ways. Total 1024 sets
// Has 4 banks. Each bank is 256KB. 256KB=8 Mats
// 16way/8Mats=2ways/mats. Each mat contain 2 ways
// Should at least keep a mat(2ways) of each bank to keep a part of cache working.
case class Sys_Config(
                        opcodes: OpcodeSet = OpcodeSet.custom0,
                        bank_num: Int = 4,
                        mat_per_bank: Int = 8,
                        total_cache_size_Byte: Int = 1048576,   // 1MB 1024KB
                        ways_per_set: Int = 16,
                        core_config: PolymorPIC_Kernal_Config = PolymorPIC_Kernal_Config()
                    )
{
    val total_valid_mats=bank_num*mat_per_bank
    val bank_64b_num=total_cache_size_Byte/bank_num/8
    val total_array_nums=bank_num*mat_per_bank*4
    val accessBankFullAddr_sigLen=log2Ceil(total_array_nums*core_config.wordlineNums)
    val total_addr=total_array_nums*core_config.wordlineNums
    val matID_sigLen=log2Ceil(total_valid_mats)

    val traceCfg=TraceCfg(pic_full_addr_len=accessBankFullAddr_sigLen)

    val cmd_enq_MMIO="h10028000".U
    val cmd_enq_MMIO_bigInt=0x10028000

    // val new_cmd_enq_MMIO="h10028200".U
    // val new_cmd_enq_MMIO_bigInt=0x10028200

    val query_tlb_MMIO="h10028100".U
    val query_tlb_MMIO_bigInt=0x10028100

    // trace IO
    val en_trace=true

    // Query mat states
    val query_clients= if(en_trace) 2 else 1
    
    // accumulator,dma_read dmaWriter p2s_R p2s_L autoLoadVec
    val accumulator_accessArray_ID=0
    val dmaReader_accessArray_ID=1
    val dmaWriter_accessArray_ID=2
    val p2s_R_accessArray_ID=3
    val p2s_L_accessArray_ID=4
    val autoLoadVec_accessArray_ID=5
    val trace_rec_accessArray_ID=6
    val bank_access_client_num= if(en_trace) (6+1) else 6
 

    // T buffer size OLD
    val transposeBufCol=128
    val transposeBuf_max_elem_col=transposeBufCol*4

    // T buffer size NEW
    val p2sR_mem_depth=2048
    assert(p2sR_mem_depth%64==0,"Error!fff")

    // accumulator
    val acc_res_num=8

    // dma once max support
    val dma_array_one_time=4
    val dma_len_sigLen= log2Ceil(dma_array_one_time*core_config.wordlineNums+1)

    // PIC mode level
    val total_levels= PIC_Level_solver()
    val pic_avail_levels= PIC_Level_solver()-1
    val nMat_per_level= (total_valid_mats/total_levels).toInt
    assert(total_valid_mats%total_levels==0,"Something bad about mapping happens.")
    val nWay_per_level= (ways_per_set/total_levels).toInt
    assert(ways_per_set%total_levels==0,"Something bad about mapping happens.")
        // valid matID range globally
        val disabled_nlevels=(total_levels-pic_avail_levels)
        val first_matID= disabled_nlevels*nMat_per_level
        val last_matID= total_levels*nMat_per_level-1
        // valid matID range per bank  // CRITICAL NOTE: addr mapping is differ in bank and globally
        val in_bank_first_matID=  (disabled_nlevels*nMat_per_level/bank_num).toInt
        val total_polymorpic_mat= last_matID-first_matID+1

    // means how max elements(not bytes) suppoorted in R's or L's one row and L
    val _R_row_offset_sigLen=15
    val _L_row_offset_sigLen=15
    val _P_row_offset_sigLen=15
    val _max_P_block_64b_per_row=(core_config.wordlineNums*32/64).toInt

    val header_path  = "./generators/PolymorPIC/ISA/ISA.h"
    val header_cpy_Paths = Array(
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/p2sL/",
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/p2sR/",
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/Accumulator/",
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/LoadStoreP/"
                            //   "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/switch/",
                                )

    // Schedular
    val LD_L_stall_tick_mask="h3F".U
    val LD_R_stall_tick_mask="hFF".U
    val EXE_stall_tick_mask="h3F".U
    val LD_P_stall_tick_mask="h3F".U
    val ACC_stall_tick_mask="h3F".U
    val STORE_P_stall_tick_mask="h3F".U


    def PIC_Level_solver(): Int=
    {
        assert(total_cache_size_Byte%bank_num==0,"This is impossible.")
        val nByte_per_Bank=(total_cache_size_Byte/bank_num).toInt
        val nByte_per_Mat= (4* core_config.wordlineNums * core_config.bitlineNums/8).toInt
        val nMat_per_Bank= nByte_per_Bank/nByte_per_Mat
        val nByte_each_way_per_Bank=nByte_per_Bank/ways_per_set

        println(s"nMat_per_Bank is ",nMat_per_Bank)

        var nMat_per_cluster_per_Bank=99999
        for(i <- 1 until ((nMat_per_Bank/2).toInt+1))
        {
            if(i*nByte_per_Mat%nByte_each_way_per_Bank==0)
            {
                if(i<nMat_per_cluster_per_Bank){nMat_per_cluster_per_Bank=i}
            }
        }
        assert(nMat_per_cluster_per_Bank!=99999,"The cache configuration can't use!")
        val level=(nByte_per_Bank/(nMat_per_cluster_per_Bank*nByte_per_Mat)).toInt
        println(s"The PIC allocate level is ",nMat_per_cluster_per_Bank)
        level
    }
    
    // Which bank
    def get_bankID(addr:UInt): UInt ={
        if(addr.getWidth==log2Ceil(total_valid_mats))
        {
            addr(log2Ceil(bank_num)-1,0)
        }
        else
        {
            assert(addr.getWidth==accessBankFullAddr_sigLen,"Wrong addr!")
            addr(core_config.core_addrLen+log2Ceil(bank_num)-1,core_config.core_addrLen)
        }
    }

    // Which mat in bank
    def get_in_bank_matID(addr:UInt): UInt ={
        if(addr.getWidth==log2Ceil(total_valid_mats))
        {
            addr(addr.getWidth-1,addr.getWidth-log2Ceil(mat_per_bank))
        }
        else
        {
            assert(addr.getWidth==accessBankFullAddr_sigLen,"Wrong addr!")
            addr(accessBankFullAddr_sigLen-1,accessBankFullAddr_sigLen-log2Ceil(mat_per_bank))
        }
    }

    // Which mat in the system
    def get_full_matID(full_addr:UInt): UInt ={
        assert(full_addr.getWidth==accessBankFullAddr_sigLen,"Please give full addr")
        full_addr(full_addr.getWidth-1,full_addr.getWidth-log2Ceil(total_valid_mats))
    }

    def get_in_mat_offset(full_addr:UInt):UInt={
        assert(full_addr.getWidth==accessBankFullAddr_sigLen,"Please give full addr")
        full_addr(core_config.core_addrLen-1,0)
    }

    // Access a row of a bank Cat(Which mat in bank,in_mat_offset)
    def get_fulladdr_sent_to_bank(full_addr:UInt): UInt ={
        assert(full_addr.getWidth==accessBankFullAddr_sigLen,"Please give full addr")
        Cat(get_in_bank_matID(full_addr),get_in_mat_offset(full_addr))
    }

    // get the matID with arrayID address
    def get_matID_from_arrayID(array_addr:UInt): UInt ={
        assert(array_addr.getWidth==log2Ceil(total_array_nums),"Please give array addr")
        array_addr(log2Ceil(total_array_nums)-1,log2Ceil(core_config.arrayNums))
    }

    // get in mat arrayID from arrayID address
    def get_in_mat_arrayID_from_arrayID(array_addr:UInt): UInt ={
        assert(array_addr.getWidth==log2Ceil(total_array_nums),"Please give array addr")
        array_addr(log2Ceil(core_config.arrayNums)-1,0)
    }

    // get array_address from full address
    def get_array_addr_from_fullAddr(full_addr:UInt): UInt ={
        assert(full_addr.getWidth==accessBankFullAddr_sigLen,"Please give full addr")
        full_addr(full_addr.getWidth-1,log2Ceil(core_config.wordlineNums))
    }

}

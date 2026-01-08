package PIC


import freechips.rocketchip.tile._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import sifive.blocks.inclusivecache.{InclusiveCacheMicroParameters,CacheParameters}
import scala.math.{max, min}


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
    val IM2COL=3.U
}

object LoadControllerClients
{
    val generalLoad=0
    val im2colLoad=1
    val p2sL=2
    val p2sR=3
    val p2sRT=4
    val numClents=5
}

object P2S_type
{
    val P2S_R=true.B
    val P2S_L=true.B
}


object PIC_Switch
{
    val ALLOC=true.B
    val FREE=false.B
    val ALLOC_define="true"
    val FREE_define="false"
}

object MMIO
{
    val cmd_enq_MMIO="h10028000".U
    val cmd_enq_MMIO_bigInt=0x10028000

    val query_tlb_MMIO="h10028100".U
    val query_tlb_MMIO_bigInt=0x10028100
}

object ISA_opCode
{
    val LOAD=0.U
    val STORE=1.U
    val EXE=2.U
    val ACC=3.U
    val QUERY=4.U       // immed
    val PIC_SWITCH=5.U
    val INIT=6.U
    val IM2COL=7.U
}

// Configuration under 1024KB LLC cache
// Each set has 16 ways. Total 1024 sets
// Has 4 banks. Each bank is 256KB. 256KB=8 Mats
// 16way/8Mats=2ways/mats. Each mat contain 2 ways
// Should at least keep a mat(2ways) of each bank to keep a part of cache working.
case class Sys_Config(
                        opcodes: OpcodeSet = OpcodeSet.custom0,
                        cache_top_param: CacheParameters,
                        cache_micro_param: InclusiveCacheMicroParameters,
                        // cache_tl_in:TLEdgeIn,
                        // cache_tl_out:TLEdgeOut,
                        numBanks: Int = 4,
                        // for default or test
                        en_custom: Boolean = false,
                        custom_cacheSizeBytes: Int= 1048576,
                        custom_waysPerSet:  Int=16
                    )
{
    // Cache base info
    val cacheSizeBytes=if(en_custom){custom_cacheSizeBytes} else{cache_top_param.sizeBytes}
    val waysPerSet=if(en_custom){custom_waysPerSet}else{cache_top_param.ways}

    // Calculate #bank（copied from bankstore）
    // val innerBytes = cache_tl_in.manager.beatBytes
    // val outerBytes = cache_tl_out.manager.beatBytes
    // val rowBytes = cache_micro_param.portFactor * max(innerBytes, outerBytes)
    // val numBanks = rowBytes / cache_micro_param.writeBytes

    // Mat core config
    val core_config=new PolymorPIC_Kernal_Config(cacheSizeBytes=cacheSizeBytes)

    // Cache is divided into several Mats, including the parts that cannot be PICed, also divided by Mat
    val matSizeBytes=core_config.matSizeBytes
    val totalMatNum=core_config.total_mats
    val matPerBank=(totalMatNum/numBanks).toInt
    println("sysCfg.matSizeBytes",matSizeBytes)
    println("sysCfg.totalMatNum",totalMatNum)
    println("sysCfg.matPerBank",matPerBank)
    assert(totalMatNum%matPerBank==0,"Something bad about mapping happens.")

    // Calculate PIC mode levels and other information
    val total_levels= PIC_Level_solver()
    val pic_avail_levels= PIC_Level_solver()-1  // reserve a level of way for cache
    val nMat_per_level= (totalMatNum/total_levels).toInt
    assert(totalMatNum%total_levels==0,"Something bad about mapping happens.")
    val nWay_per_level= (waysPerSet/total_levels).toInt
    assert(waysPerSet%total_levels==0,"Something bad about mapping happens.")
    // valid matID range globally
    val disabled_nlevels=(total_levels-pic_avail_levels)
    val first_matID= disabled_nlevels*nMat_per_level
    val last_matID= total_levels*nMat_per_level-1
    // valid matID range per bank  // CRITICAL NOTE: addr mapping is differ in bank and globally
    val in_bank_first_matID=  (disabled_nlevels*nMat_per_level/numBanks).toInt
    val total_polymorpic_mat= last_matID-first_matID+1

    println("sysCfg.total_levels",total_levels)
    println("sysCfg.first_matID",first_matID)
    println("sysCfg.last_matID",last_matID)
    println("sysCfg.nMat_per_level",nMat_per_level)

    // Other parameters
    val sizeBank_64b=cacheSizeBytes/numBanks/8
    val numArraysTotal=numBanks*matPerBank*core_config.arrayNums
    val total_64b=numArraysTotal*core_config.wordlineNums
    val accessCacheFullAddrLen=log2Ceil(total_64b)
    val accessCacheOffsetAddrLen=log2Ceil(core_config.bitlineNums/8)
    val accessCacheFullAddrWithOffsetLen=accessCacheFullAddrLen+accessCacheOffsetAddrLen

    print("sizeBank_64b",sizeBank_64b)

    // Query mat clients number
    val query_clients= 1

    val vituralization=true

    // For NCF simulation and testing only.
    val test_ncf=false
    val ddr_size_bytes=512*1024*1024
    val ddr_group_bytes=cache_top_param.sets*cache_top_param.blockBytes
    val ddr_nGroup=to_int(ddr_size_bytes,ddr_group_bytes)
    val NCF_nSet_of_one_level=to_int(nMat_per_level*matSizeBytes,cache_top_param.ways*cache_top_param.blockBytes)
    val ddr_nBlock_query_per_group=NCF_nSet_of_one_level
    val ddr_nBlock_ls_query_per_group=log2Ceil(ddr_nBlock_query_per_group)
    

    // Access Cache ID
    // accumulator,dma_read dmaWriter p2s_R p2s_L autoLoadVec
    val load_post_process_ID=0
    val bankFetch_module_ID=1
    val accumulator_accessArray_ID=2
    val autoLoadVec_accessArray_ID=3
    val p2s_L_accessArray_ID=4
    val p2s_R_accessArray_ID=5
    val p2s_R_T_accessArray_ID=6
    val numAccessCacheClient= 7

    // T buffer size NEW
    val p2sR_mem_depth=1024
    assert(p2sR_mem_depth%64==0,"Error!fff")

    // accumulator
    val numAccRes = 8
    val acc_src_maxRowNum = 1024

    // New ISA parameters; potential conflicts with the above settings require verification based on existing values.
    // Define signal widths for now; effective lengths will be calculated within the ISA logic later.
    val _L_nRow_sigLen = 8
    val _R_nRow_sigLen = log2Ceil(core_config.bitlineNums+1)
    val _RT_nRow_sigLen = log2Ceil(core_config.wordlineNums+1)
    val _P_nRow_sigLen = 11
    val _ISA_nRow_sigLen = math.max(_L_nRow_sigLen,math.max(_R_nRow_sigLen,_P_nRow_sigLen))

    val _R_bytePerRow_sigLen = log2Ceil(core_config.wordlineNums+1)
    val _RT_bytePerRow_sigLen = log2Ceil(core_config.bitlineNums+1)
    val _P_bytePerRow_sigLen = 11
    val _ISA_bytePerRow_sigLen = math.max(_R_bytePerRow_sigLen,_P_bytePerRow_sigLen)

    val maxAccessBytes= (Math.pow(2, _ISA_nRow_sigLen)*Math.pow(2,_ISA_bytePerRow_sigLen)).toInt
    val maxAccessBytesSigLen=inferWidth(maxAccessBytes)

    val offset_signLen = 15

    val pageSizeBytes=4*1024
    val virtualAddrLen= 64
    val pageIDLen= virtualAddrLen-log2Ceil(pageSizeBytes)
    val phyMemAddrLen=virtualAddrLen

    val rocc_code_name="ROCC_OPCODE"
    val rocc_code_val=0

    val roccRecendInterval=50

    // ----------------------------------------------

    // Generated header file paths 
    val header_def_path  = "./generators/rocket-chip-inclusive-cache-pic-virtual/design/craft/PolymorPIC/ISA/ISA.h"
    val header_impl_path  = "./generators/rocket-chip-inclusive-cache-pic-virtual/design/craft/PolymorPIC/ISA/ISA.c"
    val header_cpy_Paths = Array(
                                // Benchmark
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/DMA_test",
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/ACC_test",
                                "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/ISA_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/P2SL_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/P2SR_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/P2SRT_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/IM2COL_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/switch_MM",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/P2SR_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/P2SRT_test",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/switch",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/switch_MM",
                                // "/root/chipyard/toolchains/riscv-tools/riscv-tests/benchmarks/switch_linux",
                                // FPGA
                                // "/root/chipyard/baremetal/ACC",
                                // "/root/chipyard/baremetal/IM2COL",
                                // "/root/chipyard/baremetal/P2SL",
                                // "/root/chipyard/baremetal/P2SR",
                                // "/root/chipyard/baremetal/P2SRT",
                                // "/root/chipyard/baremetal/switch/head",
                                // "/root/chipyard/baremetal/switch_MM/head",
                                )

    // Schedular check intervals
    val LD_L_stall_tick_mask="h3F".U
    val LD_R_stall_tick_mask="hFF".U
    val EXE_stall_tick_mask="h3F".U
    val LD_P_stall_tick_mask="h3F".U
    val ACC_stall_tick_mask="h3F".U
    val STORE_P_stall_tick_mask="h3F".U

    // im2col configuration Do not write the maximum size as a power of 2
    // Number of levels used for im2col
    val im2col_nLevel=2
    val begin_im2colBuf_matID=last_matID-im2col_nLevel*nMat_per_level
    val end_im2colBuf_matID=last_matID
    println("sysCfg.begin_im2colBuf_matID",begin_im2colBuf_matID)
    println("sysCfg.end_im2colBuf_matID",end_im2colBuf_matID)
    val max_im2colBufSizeBytes=(end_im2colBuf_matID-begin_im2colBuf_matID+1)*matSizeBytes
    val max_featrueSize=300 // height or width
    val max_padSize=15
    val max_kernalSize=15
    val max_stride=max_kernalSize
    val max_zeroLen=(max_featrueSize+2*max_padSize)*max_padSize+max_padSize
    assert((max_featrueSize+max_padSize)*(max_featrueSize+max_padSize)<=max_im2colBufSizeBytes,"ttt")
    // Buffer address used, temporary use
    val tempBufAddr=0

    // bus width
    val busWidth=64

    // Acc num
    val accNum=4

    // cmdID size should not exceed the total size of all queues
    val cmdID_range=255 // Maximum cmdID number
    val immed_cmdID=cmdID_range // Reserved cmdID for immediate instructions
    val cmdID_sigLen=log2Ceil(cmdID_range)

    def PIC_Level_solver(): Int=
    {
        assert(cacheSizeBytes%numBanks==0,"This is impossible.")
        val nByte_per_Bank=(cacheSizeBytes/numBanks).toInt
        val nByte_per_Mat= (core_config.arrayNums* core_config.wordlineNums * core_config.bitlineNums/8).toInt
        val nByte_each_way_per_Bank=nByte_per_Bank/waysPerSet

        var nMat_per_cluster_per_Bank=99999
        for(i <- 1 until ((matPerBank/2).toInt+1))
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
        if(addr.getWidth==log2Ceil(totalMatNum))
        {
            addr(log2Ceil(numBanks)-1,0)
        }
        else
        {
            assert(addr.getWidth==accessCacheFullAddrLen,"Wrong addr!")
            addr(core_config.core_addrLen+log2Ceil(numBanks)-1,core_config.core_addrLen)
        }
    }

    // Which mat in bank
    def get_in_bank_matID(addr:UInt): UInt ={
        if(addr.getWidth==log2Ceil(totalMatNum))
        {
            addr(addr.getWidth-1,addr.getWidth-log2Ceil(matPerBank))
        }
        else
        {
            assert(addr.getWidth==accessCacheFullAddrLen,"Wrong addr!")
            addr(accessCacheFullAddrLen-1,accessCacheFullAddrLen-log2Ceil(matPerBank))
        }
    }

    // Which mat in the system
    def get_full_matID(full_addr:UInt): UInt ={
        assert(full_addr.getWidth==accessCacheFullAddrLen,"Please give full addr")
        full_addr(full_addr.getWidth-1,full_addr.getWidth-log2Ceil(totalMatNum))
    }

    def get_in_mat_offset(full_addr:UInt):UInt={
        assert(full_addr.getWidth==accessCacheFullAddrLen,"Please give full addr")
        full_addr(core_config.core_addrLen-1,0)
    }

    // Access a row of a bank Cat(Which mat in bank,in_mat_offset)
    def get_fulladdr_sent_to_bank(full_addr:UInt): UInt ={
        assert(full_addr.getWidth==accessCacheFullAddrLen,"Please give full addr")
        Cat(get_in_bank_matID(full_addr),get_in_mat_offset(full_addr))
    }

    // get the matID with arrayID address
    def get_matID_from_arrayID(array_addr:UInt): UInt ={
        assert(array_addr.getWidth==log2Ceil(numArraysTotal),"Please give array addr")
        array_addr(log2Ceil(numArraysTotal)-1,log2Ceil(core_config.arrayNums))
    }

    // get in mat arrayID from arrayID address
    def get_in_mat_arrayID_from_arrayID(array_addr:UInt): UInt ={
        assert(array_addr.getWidth==log2Ceil(numArraysTotal),"Please give array addr")
        array_addr(log2Ceil(core_config.arrayNums)-1,0)
    }

    // get array_address from full address
    def get_array_addr_from_fullAddr(full_addr:UInt): UInt ={
        assert(full_addr.getWidth==accessCacheFullAddrLen,"Please give full addr")
        full_addr(full_addr.getWidth-1,log2Ceil(core_config.wordlineNums))
    }
    
    // get MatID from inner Bank Addr 
    def get_MatID_from_inner_Bank_Addr(in_bank_addr:UInt): UInt ={
        val in_bank_addr_len=log2Ceil(matPerBank)+core_config.core_addrLen
        assert(in_bank_addr.getWidth==in_bank_addr_len,"Please give inner_Bank_Addr")
        in_bank_addr(core_config.core_addrLen+log2Ceil(matPerBank)-1,core_config.core_addrLen)
    }

    // get wayID from inner Bank Addr 
    def get_wayID_from_inner_Bank_Addr(in_bank_addr:UInt): UInt ={
        val in_bank_addr_len=log2Ceil(matPerBank)+core_config.core_addrLen
        assert(in_bank_addr.getWidth==in_bank_addr_len,"Please give inner_Bank_Addr")
        in_bank_addr(log2Ceil(64*8/64)+log2Ceil(waysPerSet)-1,log2Ceil(64*8/64))
    }

    // get levelID from inner Bank Addr 
    def get_levelID_from_inner_Bank_Addr(in_bank_addr:UInt): UInt ={
        val in_bank_addr_len=log2Ceil(matPerBank)+core_config.core_addrLen
        assert(in_bank_addr.getWidth==in_bank_addr_len,"Please give inner_Bank_Addr")
        in_bank_addr(in_bank_addr_len-1,in_bank_addr_len-log2Ceil(total_levels))
    }

    // get levelID from inner Bank MatID
    def get_levelID_from_inner_Bank_matID(in_bank_matID:UInt): UInt ={
        val in_bank_matID_len=log2Ceil(matPerBank)
        assert(in_bank_matID.getWidth==in_bank_matID_len,"Please give in_bank_matID")
        in_bank_matID(in_bank_matID_len-1,in_bank_matID_len-log2Ceil(total_levels))
    }

    // gen a full addr
    def gen_fullAddr_Int(matID:Int,arrayAddr:Int): Int ={
        assert(first_matID<=matID,"Please give correct full matID")
        assert(matID<=last_matID,"Please give correct full matID")
        assert(0<=arrayAddr,"Please give correct full matID")
        assert(arrayAddr<=core_config.total_64b,"Please give correct full matID")
        (matID<<(log2Ceil(core_config.total_64b)))+arrayAddr
    }

    // Infer line width
    def inferWidth(size:Int): Int ={
        val bitWidth=log2Ceil(size)
        if(Math.pow(2, bitWidth)==size)
        {return bitWidth+1}
        else{return bitWidth}
    }

    def get_C_Type(len: Int): String = len match {
        case len if len > 32 => "uint64_t"
        case len if len <= 32 && len > 16 => "uint32_t"
        case len if len > 8 && len <= 16 => "uint16_t"
        case len if len > 1 && len <= 8 => "uint8_t"
        case len if len == 1 => "bool"
        case _ => "Invalid length"  // invalid
    }

    // to int and make sure it is defintely int
    def to_int(up: Int,down:Int): Int ={
        assert(up%down==0,"Something bad about mapping happens.")
        return (up/down).toInt
    }
}

package PIC

import chisel3._
import chisel3.util._
import freechips.rocketchip.tile._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util.{DontTouch}

import form_icenet._

class PadInfo(sysCfg:Sys_Config) extends Bundle
{
    val padSize=UInt(log2Ceil(sysCfg.max_padSize).W)    // padding size
    val featrueBytesPerRow=UInt(log2Ceil(sysCfg.max_featrueSize).W)   // number of feature bytes per row to skip before padding
    val featrueRow=UInt(log2Ceil(sysCfg.max_featrueSize).W) // number of feature rows
}


class PaddingUnit(sysCfg:Sys_Config)(implicit p: Parameters) extends Module
{
    val beatBytes=sysCfg.busWidth/8
    val io = IO(new Bundle {
        // req
        val pad_req=Flipped(Decoupled(new PadInfo(sysCfg)))

        // dataIn
        val streamIn=Flipped(Decoupled(new StreamChannel(sysCfg.core_config.bitlineNums)))
        // dataOut
        val streamOut=Decoupled(new StreamChannel(sysCfg.core_config.bitlineNums))

        // one padding finish
        val busy=Output(Bool())
    })

    // state
    val sIdle :: rev_data :: split_enq_tail ::padding::split_enq_head::Nil = Enum(5)
    val state=RegInit(sIdle)
    io.busy := (state=/=sIdle)&(!io.streamOut.valid)
    io.pad_req.ready:= (state===sIdle)

    // req info
    val padSize=RegInit(0.U(log2Ceil(sysCfg.max_padSize).W)) // padding size
    val featrueBytesPerRow=RegInit(0.U(log2Ceil(sysCfg.max_featrueSize).W))  // number of feature bytes per row
    val featrueRow=RegInit(0.U(log2Ceil(sysCfg.max_featrueSize).W)) // fmap rows
    
    // number of feature bytes received per row
    val featrueBytesCntPerRow=RegInit(0.U((log2Ceil(sysCfg.max_featrueSize).W)))
    // number of feature rows received
    val featrueRowCnt=RegInit(0.U((log2Ceil(sysCfg.max_featrueSize).W)))
    // whether it is the first padding
    val isFirstPadding = (featrueRowCnt===0.U)
    val isLastPadding = (featrueRowCnt===featrueRow)
    // number of zeros padded this time
    val paddingBytesCnt=RegInit(0.U((log2Ceil(sysCfg.max_zeroLen).W)))
    // number of zeros to pad this time
    val paddingZeroTotalNum=Mux(isLastPadding|isFirstPadding,padSize*((padSize<<1)+featrueBytesPerRow+1.U),
                                padSize<<1)


    val dataQueue=Module(new Queue(new StreamChannel(sysCfg.core_config.bitlineNums), 2))

    // temporarily store the case that needs to be split
    val dataIn=Reg(new StreamChannel(sysCfg.core_config.bitlineNums))
    val rKeepPos=PriorityEncoder(dataIn.keep)

    dataQueue.io.deq<>io.streamOut
    dataQueue.io.enq.bits.data:=io.streamIn.bits.data
    dataQueue.io.enq.bits.keep:=io.streamIn.bits.keep
    dataQueue.io.enq.bits.last:=io.streamIn.bits.last
    // If no padding is needed, connect directly; otherwise, follow the state machine
    dataQueue.io.enq.valid:=Mux(state===sIdle,io.streamIn.valid,false.B)
    io.streamIn.ready:=Mux(state===sIdle,dataQueue.io.enq.ready,false.B)

    val padBeforeFirstRow=RegInit(false.B)


    switch(state)
    {
        is(sIdle)
        {
            when(io.pad_req.fire)
            {
                padSize:=io.pad_req.bits.padSize
                featrueBytesPerRow:=io.pad_req.bits.featrueBytesPerRow
                featrueRow:=io.pad_req.bits.featrueRow
                padBeforeFirstRow:=true.B
                featrueRowCnt:=0.U
                state:=rev_data
            }
        }
        is(rev_data)
        {
            // Data to enqueue
            val peekDataIn=io.streamIn.bits
            val peekDataInBytes=PopCount(peekDataIn.keep)
            val willnotFull=(featrueBytesCntPerRow+peekDataInBytes)<=featrueBytesPerRow
            val willFull=((featrueBytesCntPerRow+peekDataInBytes)>featrueBytesPerRow)
            dataQueue.io.enq.valid:=willnotFull&io.streamIn.valid&(!padBeforeFirstRow)

            // Conduct Enqueue
            when(dataQueue.io.enq.fire)
            {
                // Update featrueBytesCntPerRow
                featrueBytesCntPerRow:=featrueBytesCntPerRow+peekDataInBytes
                // Data from streamChannel will definitely not be last
                dataQueue.io.enq.bits.last:=false.B
                // receive
                io.streamIn.ready:=true.B

                // Conduct padding
                // 1.a featrueBytesCntPerRow (number of feature bytes received in the current row) === featrueBytesPerRow can directly proceed to padding
                when((featrueBytesCntPerRow+peekDataInBytes)===featrueBytesPerRow)
                {
                    // Reset current row count
                    featrueBytesCntPerRow:=0.U
                    // dataIn.keep cleared to 0, indicating no split
                    dataIn.keep:=0.U
                    // Completed receiving one row
                    featrueRowCnt:=featrueRowCnt+1.U

                    state:=padding
                }
            }

            
            // 1.b Padding before the first row.
            when(padBeforeFirstRow)
            {
                dataIn.keep:=0.U
                padBeforeFirstRow:=false.B
                state:=padding
            }

            // 2. `io.streamIn.valid` is asserted (data is available), but incoming data will cause an overflow.
            when(io.streamIn.valid & willFull)
            {
                // Receive and temporarily store this input.
                io.streamIn.ready:=true.B
                dataIn:=io.streamIn.bits

                state:=split_enq_tail
            }

        }
        is(split_enq_tail)
        {
            val curFeatureRowTailNum=featrueBytesPerRow-featrueBytesCntPerRow

            // enq dataqueue
            dataQueue.io.enq.valid:= true.B
            dataQueue.io.enq.bits.data:=dataIn.data
            dataQueue.io.enq.bits.last:=false.B
            val keep_lsb=Cat((0 until beatBytes).map(
                    i => (i.U >= rKeepPos) && (i.U <= rKeepPos+curFeatureRowTailNum-1.U)).reverse)
            dataQueue.io.enq.bits.keep:=keep_lsb
            when(dataQueue.io.enq.fire)
            {
                dataIn.keep:=(~keep_lsb)&dataIn.keep

                // Acceptance of one full row of the feature map is now considered complete.
                featrueRowCnt:=featrueRowCnt+1.U
                // clear
                featrueBytesCntPerRow:=0.U

                state:=padding
            }
        }
        is(padding)
        {
            val leftZeros=(paddingZeroTotalNum-paddingBytesCnt)
            dataQueue.io.enq.valid:= (leftZeros>0.U)
            dataQueue.io.enq.bits.data:=0.U

            when(dataQueue.io.enq.fire)
            {
                val zeroNumByte=Mux(leftZeros>(beatBytes).U,(beatBytes).U,leftZeros)
                val last= ((paddingBytesCnt+zeroNumByte)===paddingZeroTotalNum)&isLastPadding
                dataQueue.io.enq.bits.keep:=Cat((0 until beatBytes).map(
                    i => (i.U >= 0.U) && (i.U < zeroNumByte)).reverse)
                dataQueue.io.enq.bits.last:=last
                paddingBytesCnt:=paddingBytesCnt+zeroNumByte
            }

            // All 0 is passed
            when(leftZeros===0.U)
            {
                paddingBytesCnt:=0.U
                // Completed receiving one full row of the feature map
                // featrueRowCnt:=featrueRowCnt+1.U
                // Check if there is any remaining data previously fetched from streamIn; only continue receiving new data if none remains
                state:=Mux(dataIn.keep>0.U,split_enq_head,
                            Mux(isLastPadding,sIdle,rev_data))
            }
            padBeforeFirstRow
        }
        is(split_enq_head)
        {
            // Handle the case where one head data occupies multiple rows
            dataQueue.io.enq.valid:= true.B
            dataQueue.io.enq.bits.data:=dataIn.data
            dataQueue.io.enq.bits.last:=false.B
            val tailBytes=PopCount(dataIn.keep)
            val overflowOneRow_fillOneRow= tailBytes>=featrueBytesPerRow // Remaining portion will overflow the current row or exactly fill the current row
            val bytesToSend=Mux(!overflowOneRow_fillOneRow,tailBytes,featrueBytesPerRow)
            val keep=Cat((0 until beatBytes).map(
                    i => (i.U >= rKeepPos) && (i.U <= rKeepPos+bytesToSend-1.U)).reverse)
            dataQueue.io.enq.bits.keep:=keep
            when(dataQueue.io.enq.fire)
            {
                // Now it is a new row, if the current row can be filled, the pointer is reset to zero
                featrueBytesCntPerRow:=Mux(overflowOneRow_fillOneRow,0.U,bytesToSend)
                dataIn.keep:=(~keep)&dataIn.keep
                
                // If the current row is exactly filled or not filled, return to rev_data, there will be corresponding logic to handle
                // If the current row is filled, and there is extra data (i.e., the tail part can fill several rows), you can directly jump to padding
                featrueRowCnt:=Mux(overflowOneRow_fillOneRow,featrueRowCnt+1.U,featrueRowCnt)
                state:=Mux(overflowOneRow_fillOneRow,padding,
                            rev_data)
            }
        }
    }
}
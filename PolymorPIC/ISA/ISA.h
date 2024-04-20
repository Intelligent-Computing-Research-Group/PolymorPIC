#include <stdint.h>
#include <stdbool.h>
#include "rocc.h"

#define ROCC_OPCODE 0

#define ACC_32BIT true
#define ACC_16BIT false
#define TRACE_EN 1
#define TRACE_DEPTH 1024

void LOAD(
	uint32_t baseDRAM_Addr,
	uint32_t basePIC_Addr,	//The full addr in PIC, specifying which row.
	uint16_t LoadLen,	//Load P:10bits. How many 64b per row in P_block; P2SR: nCol of R block (len=11b).; P2SL: nRow of L block (len=10b);
	uint16_t Offset,	//15bits. In p2s, it is the num of row(R)/col(L) elems in the whole R/L. In Load P, it is the num of 64b each whole P row.
	uint8_t nBuf,	//Num of Bufs configured in each Mat. This param is used exclusively by P2SR.
	uint8_t nBit,	//The bit width of elem in R/L. This param is used exclusively by P2SR/L and should -1.(e.g., if width=8, 7->nBit.)
	uint16_t P_block_row,	//10bits. How many rows in P_block. Need to be reduced by one!!Ignore if use p2s!
	bool Split,	//If conduct P2S. Used by P2SR/L. (Split=1,Transpose=1)=>P2SR  (Split=1,Transpose=0)=>P2SL  (Split=0,Transpose=0)=>LoadP
	bool Transpose	//If conduct Transpose. Used by P2SR/L.
)
{
	uint64_t rs1=0;
	rs1=baseDRAM_Addr;
	rs1=(rs1<<18)+basePIC_Addr;
	rs1=(rs1<<14)+LoadLen;

	uint32_t rs2=0;
	rs2=Offset;
	rs2=(rs2<<2)+nBuf;
	rs2=(rs2<<3)+nBit;
	rs2=(rs2<<10)+P_block_row;
	rs2=(rs2<<1)+Split;
	rs2=(rs2<<1)+Transpose;

	ROCC_INSTRUCTION_SS(ROCC_OPCODE,rs1,rs2,0);

}

void STORE_P(
	uint32_t baseDRAM_Addr,
	uint32_t basePIC_Addr,
	uint16_t P_block_64b_per_row,	//Store P:10bits. How many 64b per row in P_block;
	uint16_t Offset,	//15bits. In Store P, it is the num of 64b each whole P row.
	uint16_t P_block_row	//10bits.How many rows in P_block. Need to be reduced by one!!
)
{
	uint64_t rs1=0;
	rs1=baseDRAM_Addr;
	rs1=(rs1<<18)+basePIC_Addr;
	rs1=(rs1<<14)+P_block_64b_per_row;

	uint32_t rs2=0;
	rs2=Offset;
	rs2=(rs2<<10)+P_block_row;
	rs2=(rs2<<7);

	ROCC_INSTRUCTION_SS(ROCC_OPCODE,rs1,rs2,1);

}

void ACC(
	uint32_t PIC_Addr_src,	//The base full address of the src.
	uint32_t PIC_Addr_dest,	//The base full address of the dest.
	uint16_t AccLen,	//The number of row(64b) to  acc. Valid bit width is 15
	uint8_t SrcNum,	//The SrcNum. Each src is located is the same relative address in the neibour mat!
	bool LoadDest,	//If it needs to load the value from dest and add it to src.
	bool BitWidth,	//The bitWdth of elem. 1->32b,0->16b
	bool If_last,	//One bit, if it is the last store of the calculation.
	bool need_P	//One bit, if it need to load p from dram.
)
{
	uint64_t rs1=0;
	rs1=PIC_Addr_src;
	rs1=(rs1<<18)+PIC_Addr_dest;
	rs1=(rs1<<16)+AccLen;
	rs1=(rs1<<12);

	uint32_t rs2=0;
	rs2=SrcNum;
	rs2=(rs2<<1)+LoadDest;
	rs2=(rs2<<1)+BitWidth;
	rs2=(rs2<<1)+If_last;
	rs2=(rs2<<1)+need_P;
	rs2=(rs2<<24);

	ROCC_INSTRUCTION_SS(ROCC_OPCODE,rs1,rs2,3);

}

void EXE(
	uint32_t PIC_Addr_R,	//Which mat to activate calculation. Only need matID, not full addr.
	uint32_t PIC_Addr_L,	//Where is the first L row saved.
	uint16_t R_Valid_nCols,	//How many R cols.(In the array, it is the num of valid row of R)
	uint8_t nBuf,	//How many arrays used as buffer to keep P's partial sum
	uint8_t nCal,	//How many arrays used to conduct MAC.(The left array except used as buffers will be MAC mode.However, not all of them must work.)
	uint8_t Base_R_Bit,	//The first R bit slice's bit position. The left R bit slices are +1 one by one based on it.
	uint8_t L_Precision,	//What is L bitWidth. It should -1. e.g., if it is 8b, write 7 here
	uint16_t L_Block_Row,	//How many row in L.
	bool Sign,	//If value is signed.
	bool BitWidth	//The accWidth. 32b->true;16b->false
)
{
	uint64_t rs1=0;
	rs1=PIC_Addr_R;
	rs1=(rs1<<18)+PIC_Addr_L;
	rs1=(rs1<<16)+R_Valid_nCols-1;
	rs1=(rs1<<12);

	uint32_t rs2=0;
	rs2=nBuf;
	rs2=(rs2<<2)+nCal;
	rs2=(rs2<<3)+Base_R_Bit;
	rs2=(rs2<<3)+L_Precision;
	rs2=(rs2<<9)+L_Block_Row;
	rs2=(rs2<<1)+Sign;
	rs2=(rs2<<1)+BitWidth;
	rs2=(rs2<<11);

	ROCC_INSTRUCTION_SS(ROCC_OPCODE,rs1,rs2,2);

}

int QUERY_STATE()
{


	unsigned long rd = -1;
	ROCC_INSTRUCTION_D(ROCC_OPCODE,rd,4);

	return rd;
}

void PIC_SWITCH(
	bool opType,	//OpType. Bool(true) is alloc, Bool(false) is FREE.
	uint8_t nLevels	//How many clusters of mats to Alloc. Total 7 is available.
)
{
	uint64_t rs1=0;
	rs1=opType;
	rs1=(rs1<<3)+nLevels;
	rs1=(rs1<<60);


	ROCC_INSTRUCTION_S(ROCC_OPCODE,rs1,5);

}

void SAVE_TRACE(
	uint32_t pic_dest_addr	//Full pic addr.
)
{
	uint64_t rs1=0;
	rs1=pic_dest_addr;
	rs1=(rs1<<47);


	ROCC_INSTRUCTION_S(ROCC_OPCODE,rs1,6);

}

void INIT()
{


	ROCC_INSTRUCTION(ROCC_OPCODE,7);

}


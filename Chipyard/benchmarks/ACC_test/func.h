#ifndef FUNC_H
#define FUNC_H

#include <stdio.h>
#include "data.h"
#include "config.h"


void printBlockHexArray_16(uint16_t *array, int beginRow, int beginCol,int nRows,int nCols)
{
	int i, j;
	printf("RowIdx/EndRowIdx of Total nRows in big matrix.\n");
	for (i = beginRow; i < beginRow+nRows; i++) {
		printf("%4d/%d\t\t",i,nRows-1);
		for (j = beginCol; j < beginCol+nCols; j++) {
			int index = i * nCols + j;
			printf("0x%04x ", array[index]);
		}
	printf("\n");
	}
}



void printBlockHexArray_32(uint32_t *array, int beginRow, int beginCol,int nRows,int nCols)
{
	int i, j;
	printf("RowIdx/EndRowIdx of Total nRows in big matrix.\n");
	for (i = beginRow; i < beginRow+nRows; i++) {
		printf("%4d/%d\t\t",i,nRows-1);
		for (j = beginCol; j < beginCol+nCols; j++) {
			int index = i * nCols + j;
			printf("0x%08x ", array[index]);
		}
	printf("\n");
	}
}

void validation_16(
        uint16_t *must_true, 
        uint16_t *may_false,
        int nRow,
        int nCol
)
{
    
    printf("ACC check start!\n");
    for(int row = 0; row < nRow; row++)
    {
        for (int col = 0; col < nCol; col++)
        {
            int index=row*nCol+col;
            if(must_true[index]!=(may_false[index]))
            {printf("Error->row:%d;col:%d\ttrue/false:%016x/%016x\n",
                    row,col,must_true[index],may_false[index]);}
        }
    }

    printf("This Acc result is all correct!\n");
    
}


void validation_32(
        uint32_t *must_true, 
        uint32_t *may_false,
        int nRow,
        int nCol
)
{
    
    printf("ACC check start!\n");
    for(int row = 0; row < nRow; row++)
    {
        for (int col = 0; col < nCol; col++)
        {
            int index=row*nCol+col;
            if(must_true[index]!=(may_false[index]))
            {printf("Error->row:%d;col:%d\ttrue/false:%016x/%016x\n",
                    row,col,must_true[index],may_false[index]);}
        }
    }

    printf("This Acc result is all correct!\n");
    
}


#endif
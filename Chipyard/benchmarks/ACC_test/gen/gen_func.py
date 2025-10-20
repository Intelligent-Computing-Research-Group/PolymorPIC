import gen_data

def wrtie_printBlockedArrayFunc(file,dWidth):
    func_str=f"void printBlockHexArray_{dWidth}({gen_data.get_dType_str(dWidth)} *array, int beginRow, int beginCol,int nRows,int nCols)\n"
    func_str+="{\n"
    func_str+="\tint i, j;\n"
    func_str+="\tprintf(\"RowIdx/EndRowIdx of Total nRows in big matrix.\\n\");\n"
    func_str+="\tfor (i = beginRow; i < beginRow+nRows; i++) {\n"

    func_str+="\t\tprintf(\"%4d/%d\\t\\t\",i,nRows-1);\n"
    func_str+="\t\tfor (j = beginCol; j < beginCol+nCols; j++) {\n"


    func_str+="\t\t\tint index = i * nCols + j;\n"
    func_str+=f"\t\t\tprintf(\"0x%0{int(dWidth/4)}x \", array[index]);\n"
    func_str+="\t\t}\n"

    func_str+="\tprintf(\"\\n\");\n"
    func_str+="\t}\n"

    func_str+="}\n\n"
    
    file.write("\n")
    file.write("\n")
    file.write(func_str)

def write_Validation(file,dWidth):
    #############################
    # Only check feature map part
    #############################
    func_str=f'''void validation_{dWidth}(
        {gen_data.get_dType_str(dWidth)} *must_true, 
        {gen_data.get_dType_str(dWidth)} *may_false,
        int nRow,
        int nCol
)
{{
    '''

    file.write(func_str)
    
    func_str=f'''
    printf("ACC check start!\\n");
    for(int row = 0; row < nRow; row++)
    {{
        for (int col = 0; col < nCol; col++)
        {{
            int index=row*nCol+col;
            if(must_true[index]!=(may_false[index]))
            {{printf("Error->row:%d;col:%d\\ttrue/false:%016x/%016x\\n",
                    row,col,must_true[index],may_false[index]);}}
        }}
    }}

    printf("This Acc result is all correct!\\n");
    '''

    file.write(func_str)
    file.write("\n")
    file.write("}\n\n\n")
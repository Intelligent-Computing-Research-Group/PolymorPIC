def write_config(file,test_id,
                    len_64b,
                    srcArrayID,
                    srcNum,
                    destArrayID,
                    bitWidth
                    ):

    file.write(f"\n")
    file.write(f"// ########## testID:{test_id} configurations ############\n")

    file.write(f"\n")
    
    file.write(f'int {test_id}_len_64b = '+str(len_64b)+';\n')
    file.write(f'int {test_id}_srcArrayID = '+str(srcArrayID)+';\n')
    file.write(f'int {test_id}_srcNum = '+str(srcNum)+';\n')
    file.write(f'int {test_id}_destArrayID = '+str(destArrayID)+';\n')
    file.write(f'bool {test_id}_bitWidth = '+str(bitWidth)+';\n')
  
    file.write(f"\n")
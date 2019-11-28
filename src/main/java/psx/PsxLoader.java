/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package psx;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import docking.widgets.OptionDialog;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.plugin.core.analysis.DefaultDataTypeManagerService;
import ghidra.app.util.Option;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.AbstractLibrarySupportLoader;
import ghidra.app.util.opinion.LoadSpec;
import ghidra.app.util.opinion.Loader;
import ghidra.app.util.parser.FunctionSignatureParser;
import ghidra.feature.fid.db.FidFile;
import ghidra.feature.fid.db.FidFileManager;
import ghidra.framework.Application;
import ghidra.framework.model.DomainObject;
import ghidra.framework.store.LockException;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.data.CompositeDataTypeImpl;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.DataUtilities;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.UnionDataType;
import ghidra.program.model.data.DataUtilities.ClearDataMode;
import ghidra.program.model.data.EnumDataType;
import ghidra.program.model.data.FunctionDefinitionDataType;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.ContextChangeException;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryBlockException;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.exception.NotFoundException;
import ghidra.util.task.TaskMonitor;
import psyq.DetectPsyQ;
import psyq.sym.SymDef;
import psyq.sym.SymDefTypePrim;
import psyq.sym.SymFile;
import psyq.sym.SymFunc;
import psyq.sym.SymObject;
import psyq.sym.SymStructUnionEnum;
import psyq.sym.SymName;

public class PsxLoader extends AbstractLibrarySupportLoader {
	
	private static final long DEF_RAM_BASE = 0x80000000L;
	private static final long RAM_SIZE = 0x200000L;
	private static final long __heapbase_off = -0x30;
	private static final long _sbss_off = -0x28;
	private static final long _sdata_off = -0x20;
	
	private static final byte[] MAIN_SIGN_47 = new byte[]{
			0x00, 0x00, 0x00, 0x0C, // jal main
			0x00, 0x00, 0x00, 0x00, // nop
			0x01, 0x00, 0x04, 0x3C, 0x00, 0x00, 0x00, 0x00, // la $a0, dword_xx010000
			0x01, 0x00, 0x05, 0x3C, 0x00, 0x00, 0x00, 0x00, // la $a1, dword_xx010000
			0x00, 0x00, 0x00, 0x0C, // jal __sn_cpp_structors
			0x00, 0x00, 0x00, 0x00, // nop
			0x4D, 0x00, 0x00, 0x00, // break 1
	};

	private static final byte[] MAIN_SIGN_MASK_47 = new byte[]{
			0x00, 0x00, 0x00, (byte)0xFF, // jal main
			(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, // nop
			(byte)0xFF, 0x00, (byte)0xFF, (byte)0xFF, 0x00, 0x00, 0x00, 0x00, // la $a0, dword_xx010000
			(byte)0xFF, 0x00, (byte)0xFF, (byte)0xFF, 0x00, 0x00, 0x00, 0x00, // la $a1, dword_xx010000
			0x00, 0x00, 0x00, (byte)0xFF, // jal __sn_cpp_structors
			(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, // nop
			(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, // break 1
	};
	
	private static final byte[] MAIN_SIGN_37_46 = new byte[]{
			0x00, 0x00, 0x00, 0x00, // nop
			0x00, 0x00, 0x00, 0x0C, // jal __sn_cpp_structors
			0x00, 0x00, 0x00, 0x00, // nop
			0x4D, 0x00, 0x00, 0x00, // break 1
	};

	private static final byte[] MAIN_SIGN_MASK_37_46 = new byte[]{
			(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, // nop
			0x00, 0x00, 0x00, (byte)0xFF, // jal main
			(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, // nop
			(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, // break 1
	};
	
	public static final String PSX_LOADER = "PSX Executables Loader";
	
	private PsxExe psxExe;
	
	private static final String OPTION_NAME = "RAM Base Address: ";
	private long ramBase = DEF_RAM_BASE;

	@Override
	public String getName() {
		return PSX_LOADER;
	}

	@Override
	public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
		List<LoadSpec> loadSpecs = new ArrayList<>();

		BinaryReader reader = new BinaryReader(provider, true);
		
		psxExe = new PsxExe(reader);
		
		if (psxExe.isParsed()) {
			loadSpecs.add(new LoadSpec(this, 0, new LanguageCompilerSpecPair("MIPS:LE:32:default", "default"), true));
		}

		return loadSpecs;
	}
	
	@Override
	public List<Option> getDefaultOptions(ByteProvider provider, LoadSpec loadSpec, DomainObject domainObject,
			boolean loadIntoProgram) {
		
		List<Option> list = new ArrayList<>();
		
		list.add(new PsxBaseChooser(OPTION_NAME, ramBase, PsxBaseChooser.class, Loader.COMMAND_LINE_ARG_PREFIX + "-ramStart"));
		
		return list;
	}
	
	@Override
	public String validateOptions(ByteProvider provider, LoadSpec loadSpec, List<Option> options, Program program) {
		for (Option option : options) {
			String optName = option.getName();
			if (optName.equals(OPTION_NAME)) {
				ramBase = Long.decode((String)option.getValue());
				break;
			}
		}

		return null;
	}

	@Override
	protected void load(ByteProvider provider, LoadSpec loadSpec, List<Option> options, Program program, TaskMonitor monitor, MessageLog log)
			throws IOException {

		if (!psxExe.isParsed()) {
			monitor.setMessage(String.format("%s : Cannot load", getName()));
			return;
		}
		
		monitor.setMessage(String.format("%s : Start loading", getName()));
		
		FlatProgramAPI fpa = new FlatProgramAPI(program, monitor);
		
		long realRamBase = psxExe.getInitPc() & 0xFF000000L;
		
		try {
			TimeUnit.SECONDS.sleep(1);
		} catch (InterruptedException e) {
		}
		
		if (realRamBase != ramBase) {
			if (ramBase == DEF_RAM_BASE) {
				ramBase = realRamBase;
			} else {
				switch (OptionDialog.showYesNoDialogWithNoAsDefaultButton(null, "Question",
						"RAM Base Address in the \"PS-X EXE\" header differs from the specified one!\n\n" +
			            "Do you want to continue?\n" +
						String.format("YES: Use specified address           - 0x%08X.\n", ramBase) +
				        String.format(" NO: Use address based on the binary - 0x%08X.\n", realRamBase))) {
				case OptionDialog.YES_OPTION: {
				} break;
				case OptionDialog.NO_OPTION: {
					ramBase = realRamBase;
				} break;
				}
			}
		}
		
		createSegments(provider, fpa, log);
		
		setFunction(program.getSymbolTable(), fpa, fpa.toAddr(psxExe.getInitPc()), "start", true, true, log);

		setRegisterValue(fpa, "gp", psxExe.getInitPc(), psxExe.getInitGp(), log);
		setRegisterValue(fpa, "sp", psxExe.getInitPc(), psxExe.getSpBase() + psxExe.getSpOff(), log);
		
		Address romStart = fpa.toAddr(psxExe.getRomStart());
		loadPsyqFidFile(program.getMemory(), romStart, log);
		findAndAppyMain(provider, fpa, romStart, log);
		
		monitor.setMessage(String.format("%s : Loading done", getName()));
		
		try {
			TimeUnit.SECONDS.sleep(1);
		} catch (InterruptedException e) {
		}
		
		if (OptionDialog.YES_OPTION == OptionDialog.showYesNoDialogWithNoAsDefaultButton(null,
				"Question", "Do you have .SYM file for this executable?")) {
			String symPath = showSelectFile("Select file...", program.getExecutablePath());
			SymFile symFile = SymFile.fromBinary(symPath);
			applySymbols(program.getSymbolTable(), fpa, symFile, log);
		}
	}
	
	@SuppressWarnings("incomplete-switch")
	private static void applySymbols(SymbolTable st, FlatProgramAPI fpa, SymFile symFile, MessageLog log) {
		SymObject[] objects = symFile.getObjects();
		
		for (SymObject obj : objects) {
			Address addr = fpa.toAddr(obj.getOffset());
			
			try {
				if (obj instanceof SymFunc) {
					SymFunc sf = (SymFunc)obj;
					setFunction(st, fpa, addr, sf.getFuncName(), true, false, log);
					setFunctionArguments(fpa, sf, log);
					fpa.setPlateComment(addr, String.format(
							"File: %s\n" +
					        "Return type: %s",
					        sf.getFileName(), sf.getReturnTypeAsString()));
				} else if (obj instanceof SymName) {
					SymName sn = (SymName)obj;
					st.createLabel(addr, sn.getObjectName(), SourceType.ANALYSIS);
				} else if (obj instanceof SymStructUnionEnum) {
					SymStructUnionEnum ssu = (SymStructUnionEnum)obj;
					SymDefTypePrim type = ssu.getType();
					SymDef[] fields = ssu.getFields();
					
					switch (type) {
					case UNION: {
						UnionDataType udt = new UnionDataType(ssu.getName());
						
						for (SymDef field : fields) {
							// TODO: call getDataType();
							udt.add(null, field.getName(), null);
						}
					} break;
					case STRUCT: {
						StructureDataType sdt = new StructureDataType(ssu.getName(), 0);
						
						for (SymDef field : fields) {
							// TODO: call getDataType();
							sdt.add(null, field.getName(), null);
						}
					} break;
					case ENUM: {
						EnumDataType edt = new EnumDataType(ssu.getName(), 0);
						
						for (SymDef field : fields) {
							edt.add(field.getName(), field.getOffset());
						}
						
						fpa.getCurrentProgram().getDataTypeManager().addDataType(edt, DataTypeConflictHandler.DEFAULT_HANDLER);
					} break;
					}
				}
			} catch (InvalidInputException e) {
				log.appendException(e);
			}
		}
	}
	
	private static String showSelectFile(String title, String baseDir) {
		JFileChooser jfc = new JFileChooser(new File(baseDir));
		jfc.setDialogTitle(title);

		jfc.setFileFilter(new FileNameExtensionFilter("Symbols File", "sym"));
		jfc.setMultiSelectionEnabled(false);

		if (jfc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
			return jfc.getSelectedFile().getAbsolutePath();
		}

		return null;
	}
	
	private static void setRegisterValue(FlatProgramAPI fpa, String name, long startAddress, long value, MessageLog log) {
		Program program = fpa.getCurrentProgram();
		
		RegisterValue regVal = new RegisterValue(program.getRegister(name), BigInteger.valueOf(value));
		Address start = fpa.toAddr(startAddress);
		try {
			program.getProgramContext().setRegisterValue(start, start, regVal);
		} catch (ContextChangeException e) {
			log.appendException(e);
		}
	}
	
	private static void disasmInstruction(Program program, Address address) {
		DisassembleCommand cmd = new DisassembleCommand(address, null, true);
		cmd.applyTo(program, TaskMonitor.DUMMY);
	}
	
	private static void setFunction(SymbolTable st, FlatProgramAPI fpa, Address address, String name,
			boolean isFunction, boolean isEntryPoint, MessageLog log) {
		try {
			if (fpa.getInstructionAt(address) == null)
				disasmInstruction(fpa.getCurrentProgram(), address);
			
			if (isFunction) {
				fpa.createFunction(address, name);
			}
			if (isEntryPoint) {
				fpa.addEntryPoint(address);
			}
			
			if (isFunction && st.hasSymbol(address)) {
				return;
			}
			
			st.createLabel(address, name, SourceType.ANALYSIS);
		} catch (InvalidInputException e) {
			log.appendException(e);
		}
	}
	
	private static void setFunctionArguments(FlatProgramAPI fpa, SymFunc funcDef, MessageLog log) {
		try {
			DataTypeManager mgr = fpa.getCurrentProgram().getDataTypeManager();
			
			String sign = String.format("%s %s(%s)",
					funcDef.getReturnTypeAsString(),
					funcDef.getFuncName(),
					funcDef.getArgumentsAsString());

			FunctionSignatureParser parser = new FunctionSignatureParser(mgr, new DefaultDataTypeManagerService());
			FunctionDefinitionDataType fddt = parser.parse(null, sign);
			ApplyFunctionSignatureCmd cmd = new ApplyFunctionSignatureCmd(fpa.toAddr(funcDef.getOffset()), fddt, SourceType.ANALYSIS);
			cmd.applyTo(fpa.getCurrentProgram());
		} catch (Exception e) {
			log.appendException(e);
		}
	}
	
	private static void loadPsyqFidFile(Memory mem, Address startAddr, MessageLog log) {
		try {
			String psyVersion = DetectPsyQ.getPsyqVersion(mem, startAddr);
			
			if (psyVersion == null) {
				return;
			}
			
			FidFileManager fm = FidFileManager.getInstance();
			List<FidFile> fl = fm.getUserAddedFiles();
			
			for (FidFile ff : fl) {
				fm.removeUserFile(ff);
			}
			
			File fidFile = Application.getModuleDataFile(String.format("psyq%s.fidb", psyVersion)).getFile(false);
			fm.addUserFidFile(fidFile);
		} catch (MemoryAccessException | AddressOutOfBoundsException | FileNotFoundException e) {
			log.appendException(e);
		}
	}
	
	private void findAndAppyMain(ByteProvider provider, FlatProgramAPI fpa, Address searchAddress, MessageLog log) {
		Program program = fpa.getCurrentProgram();
		BinaryReader reader = new BinaryReader(provider, true);
		Memory mem = program.getMemory();
		Listing listing = program.getListing();
		SymbolTable st = program.getSymbolTable();
		
		Address mainRefAddr = mem.findBytes(searchAddress, MAIN_SIGN_47, MAIN_SIGN_MASK_47, true, TaskMonitor.DUMMY);
		
		if (mainRefAddr == null) {
			mainRefAddr = program.getMemory().findBytes(searchAddress, MAIN_SIGN_37_46, MAIN_SIGN_MASK_37_46, true, TaskMonitor.DUMMY);
			
			if (mainRefAddr == null) {
				return;
			}
			
			mainRefAddr = mainRefAddr.add(4);
		}

		Instruction jalMain = listing.getInstructionAt(mainRefAddr);
		
		if (jalMain == null) {
			return;
		}
		
		Reference[] jalMainRefs = jalMain.getReferencesFrom();
		
		if (jalMainRefs.length != 1) {
			return;
		}
		
		Address mainAddr = jalMainRefs[0].getToAddress();
		try {
			st.createLabel(mainAddr, "main", SourceType.USER_DEFINED);
		} catch (InvalidInputException e) {
			log.appendException(e);
			return;
		}
		
		Instruction heapBaseInstr_1 = listing.getInstructionAt(mainRefAddr.add(__heapbase_off));
		Instruction heapBaseInstr_2 = listing.getInstructionAt(mainRefAddr.add(__heapbase_off).add(4));
		Instruction sbssInstr1 = listing.getInstructionAt(mainRefAddr.add(_sbss_off));
		Instruction sbssInstr2 = listing.getInstructionAt(mainRefAddr.add(_sbss_off).add(4));
		Instruction sdataInstr1 = listing.getInstructionAt(mainRefAddr.add(_sdata_off));
		Instruction sdataInstr2 = listing.getInstructionAt(mainRefAddr.add(_sdata_off).add(4));
		
		if (heapBaseInstr_1 == null || heapBaseInstr_2 == null ||
				sbssInstr1 == null || sbssInstr2 == null ||
				sdataInstr1 == null || sdataInstr2 == null) {
			return;
		}
		
		Scalar heapBase1 = heapBaseInstr_1.getScalar(1);
		Object[] heapBase2 = heapBaseInstr_2.getOpObjects(1);
		Scalar sbss1 = sbssInstr1.getScalar(1);
		Object[] sbss2 = sbssInstr2.getOpObjects(1);
		Scalar sdata1 = sdataInstr1.getScalar(1);
		Scalar sdata2 = sdataInstr2.getScalar(2);
		
		if (heapBase1 == null || heapBase2 == null || heapBase2.length != 2 ||
				sbss1 == null || sbss2 == null || sbss2.length != 2 ||
				sdata1 == null || sdata2 == null) {
			return;
		}
		
		try {
			Address structPtr = fpa.toAddr((heapBase1.getUnsignedValue() << 16) + ((Scalar)(heapBase2[0])).getSignedValue());
			createNamedPtr(fpa, structPtr.getOffset(), "__heapbase", log);
			createNamedDword(fpa, structPtr.add(4).getOffset(), "__heapsize", log);

			Address _text_ptr = structPtr.add(0x08);
			createNamedPtr(fpa, _text_ptr.getOffset(), "__text", log);
			createNamedDword(fpa, _text_ptr.add(4).getOffset(), "__textlen", log);
			long _text = reader.readUnsignedInt(_text_ptr.subtract(searchAddress.subtract(PsxExe.HEADER_SIZE)));
			long _textlen = reader.readUnsignedInt(_text_ptr.add(4).subtract(searchAddress.subtract(PsxExe.HEADER_SIZE)));
			
			Address _text_addr = fpa.toAddr(_text);
			MemoryBlock _text_block = mem.getBlock(_text_addr);
			mem.split(_text_block, _text_addr);
			
			Address _rdata_addr = _text_block.getStart();
			MemoryBlock _rdata_block = mem.getBlock(_rdata_addr);
			_rdata_block.setName(".rdata");
			_rdata_block.setWrite(false);
			_rdata_block.setExecute(false);
			
			_text_block = mem.getBlock(_text_addr);
			_text_block.setName(".text");
			_text_block.setWrite(false);
			_text_block.setExecute(true);
			mem.split(_text_block, _text_addr.add(_textlen));

			Address _data_ptr = structPtr.add(0x10);
			createNamedPtr(fpa, _data_ptr.getOffset(), "__data", log);
			createNamedDword(fpa, _data_ptr.add(4).getOffset(), "__datalen", log);
			long _data = reader.readUnsignedInt(_data_ptr.subtract(searchAddress.subtract(PsxExe.HEADER_SIZE)));
			long _datalen = reader.readUnsignedInt(_data_ptr.add(4).subtract(searchAddress.subtract(PsxExe.HEADER_SIZE)));
			
			Address _data_addr = fpa.toAddr(_data);
			MemoryBlock _data_block = mem.getBlock(_data_addr);
			_data_block.setName(".data");
			_data_block.setWrite(true);
			_data_block.setExecute(false);
			
			if (_data_block.getSize() > _datalen) {
				mem.split(_data_block, _data_addr.add(_datalen));
			}
			
			Address _sdata_addr = fpa.toAddr((sdata1.getUnsignedValue() << 16) + sdata2.getSignedValue());
			MemoryBlock _sdata_block = mem.getBlock(_sdata_addr);
			_sdata_block.setName(".sdata");
			_sdata_block.setWrite(true);
			_sdata_block.setExecute(false);
			
			setRegisterValue(fpa, "gp", mainAddr.getOffset(), _sdata_addr.getOffset(), log);
			
			Address _sbss_addr = fpa.toAddr((sbss1.getUnsignedValue() << 16) + ((Scalar)(sbss2[0])).getSignedValue());
			MemoryBlock _sbss_block = mem.getBlock(_sbss_addr);
			
			if (_sbss_block.getStart().getOffset() < _sbss_addr.getOffset()) {
				mem.split(_sbss_block, _sbss_addr);
				_sbss_block = mem.getBlock(_sbss_addr);
				_sbss_block.setName(".sbss");
			}
			_sbss_block.setWrite(true);
			_sbss_block.setExecute(false);
			if (_sbss_block.isInitialized()) {
				mem.convertToUninitialized(_sbss_block);
			}
			
			Address _bss_ptr = structPtr.add(0x18);
			createNamedPtr(fpa, _bss_ptr.getOffset(), "__bss", log);
			createNamedDword(fpa, _bss_ptr.add(4).getOffset(), "__bsslen", log);
			long _bss = reader.readUnsignedInt(_bss_ptr.subtract(searchAddress.subtract(PsxExe.HEADER_SIZE)));
			long _bsslen = reader.readUnsignedInt(_bss_ptr.add(4).subtract(searchAddress.subtract(PsxExe.HEADER_SIZE)));
			
			Address _bss_addr = fpa.toAddr(_bss);
			MemoryBlock _bss_block = mem.getBlock(_bss_addr);
			mem.split(_bss_block, _bss_addr);
			_bss_block = mem.getBlock(_bss_addr);
			_bss_block.setName(".bss");
			_bss_block.setWrite(false);
			_bss_block.setExecute(false);
			if (_bss_block.isInitialized()) {
				mem.convertToUninitialized(_bss_block);
			}
			
			if (_bss_block.getSize() < _bsslen) {
				MemoryBlock block2 = mem.getBlock(_bss_addr.add(_bsslen));
				mem.join(_bss_block, block2);
				_bss_block = mem.getBlock(_bss_addr);
			}
			
			mem.split(_bss_block, _bss_addr.add(_bsslen));
			
			MemoryBlock ram = mem.getBlock(_bss_addr.add(_bsslen));
			ram.setName("RAM");
			ram.setWrite(true);
			ram.setExecute(true);
			
			createNamedDword(fpa, _sdata_addr.getOffset(), "_ramsize", log);
			createNamedDword(fpa, _sdata_addr.add(4).getOffset(), "_stacksize", log);
		} catch (IOException | MemoryBlockException | LockException | NotFoundException | DuplicateNameException e) {
			log.appendException(e);
		}
	}
	
	private void createSegments(ByteProvider provider, FlatProgramAPI fpa, MessageLog log) throws IOException {
		
		InputStream codeStream = provider.getInputStream(PsxExe.HEADER_SIZE);
		
		long ram_size_1 = psxExe.getRomStart() - ramBase;
		createSegment(fpa, null, "RAM", ramBase, ram_size_1, true, true, log);
		
		long code_size = psxExe.getRomSize();
		long code_addr = psxExe.getRomStart();
		
		createSegment(fpa, codeStream, "CODE", code_addr, code_size, false, true, log);
		
		if (psxExe.getDataAddr() != 0) {
			createSegment(fpa, null, ".data", psxExe.getDataAddr(), psxExe.getDataSize(), true, false, log);
		}
		
		if (psxExe.getBssAddr() != 0) {
			createSegment(fpa, null, ".bss", psxExe.getBssAddr(), psxExe.getBssSize(), false, false, log);
		}
		
		long code_end = psxExe.getRomEnd();
		long ram_size_2 = ramBase + RAM_SIZE - code_end;
		createSegment(fpa, null, "RAM", code_end, ram_size_2, false, true, log);
		
		createSegment(fpa, null, "CACHE", 0x1F800000L, 0x400, true, true, log);
		createSegment(fpa, null, "UNK1", 0x1F800400L, 0xC00, true, true, log);
		
		addMemCtrl1(fpa, log);
		addMemCtrl2(fpa, log);
		addPeriphIo(fpa, log);
		addIntCtrl(fpa, log);
		addDma(fpa, log);
		addTimers(fpa, log);
		addCdromRegs(fpa, log);
		addGpuRegs(fpa, log);
		addMdecRegs(fpa, log);
		addSpuVoices(fpa, log);
		addSpuCtrlRegs(fpa, log);
	}
	
	private static void addMemCtrl1(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "MCTRL1", 0x1F801000L, 0x24, true, false, log);
		
		createNamedDword(fpa, 0x1F801000L, "EXP1_BASE_ADDR", log);
		createNamedDword(fpa, 0x1F801004L, "EXP2_BASE_ADDR", log);
		createNamedDword(fpa, 0x1F801008L, "EXP1_DELAY_SIZE", log);
		createNamedDword(fpa, 0x1F80100CL, "EXP3_DELAY_SIZE", log);
		createNamedDword(fpa, 0x1F801010L, "BIOS_ROM", log);
		createNamedDword(fpa, 0x1F801014L, "SPU_DELAY", log);
		createNamedDword(fpa, 0x1F801018L, "CDROM_DELAY", log);
		createNamedDword(fpa, 0x1F80101CL, "EXP2_DELAY_SIZE", log);
		createNamedDword(fpa, 0x1F801020L, "COMMON_DELAY", log);
	}
	
	private static void addMemCtrl2(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "MCTRL2", 0x1F801060L, 4, true, false, log);
		
		createNamedDword(fpa, 0x1F801060L, "RAM_SIZE", log);
	}
	
	private static void addPeriphIo(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "IO_PORTS", 0x1F801040L, 0x20, true, false, log);
		
		createNamedDword(fpa, 0x1F801040L, "JOY_MCD_DATA", log);
		createNamedDword(fpa, 0x1F801044L, "JOY_MCD_STAT", log);
		
		createNamedWord(fpa, 0x1F801048L, "JOY_MCD_MODE", log);
		createNamedWord(fpa, 0x1F80104AL, "JOY_MCD_CTRL", log);
		createNamedWord(fpa, 0x1F80104EL, "JOY_MCD_BAUD", log);
		
		createNamedDword(fpa, 0x1F801050L, "SIO_DATA", log);
		createNamedDword(fpa, 0x1F801054L, "SIO_STAT", log);
		
		createNamedWord(fpa, 0x1F801058L, "SIO_MODE", log);
		createNamedWord(fpa, 0x1F80105AL, "SIO_CTRL", log);
		createNamedWord(fpa, 0x1F80105CL, "SIO_MISC", log);
		createNamedWord(fpa, 0x1F80105EL, "SIO_BAUD", log);
	}
	
	private static void addIntCtrl(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "INT_CTRL", 0x1F801070L, 6, true, false, log);
		
		createNamedWord(fpa, 0x1F801070L, "I_STAT", log);
		createNamedWord(fpa, 0x1F801074L, "I_MASK", log);
	}
	
	private static void addDma(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "DMA_MDEC_IN", 0x1F801080L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_MDEC_OUT", 0x1F801090L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_GPU", 0x1F8010A0L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_CDROM", 0x1F8010B0L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_SPU", 0x1F8010C0L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_PIO", 0x1F8010D0L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_OTC", 0x1F8010E0L, 0x0C, true, false, log);
		createSegment(fpa, null, "DMA_CTRL_INT", 0x1F8010F0L, 0x08, true, false, log);
		
		createNamedDword(fpa, 0x1F801080L, "DMA_MDEC_IN_MADR", log);
		createNamedDword(fpa, 0x1F801084L, "DMA_MDEC_IN_BCR", log);
		createNamedDword(fpa, 0x1F801088L, "DMA_MDEC_IN_CHCR", log);
		
		createNamedDword(fpa, 0x1F801090L, "DMA_MDEC_OUT_MADR", log);
		createNamedDword(fpa, 0x1F801094L, "DMA_MDEC_OUT_BCR", log);
		createNamedDword(fpa, 0x1F801098L, "DMA_MDEC_OUT_CHCR", log);
		
		createNamedDword(fpa, 0x1F8010A0L, "DMA_GPU_MADR", log);
		createNamedDword(fpa, 0x1F8010A4L, "DMA_GPU_BCR", log);
		createNamedDword(fpa, 0x1F8010A8L, "DMA_GPU_CHCR", log);
		
		createNamedDword(fpa, 0x1F8010B0L, "DMA_CDROM_MADR", log);
		createNamedDword(fpa, 0x1F8010B4L, "DMA_CDROM_BCR", log);
		createNamedDword(fpa, 0x1F8010B8L, "DMA_CDROM_CHCR", log);
		
		createNamedDword(fpa, 0x1F8010C0L, "DMA_SPU_MADR", log);
		createNamedDword(fpa, 0x1F8010C4L, "DMA_SPU_BCR", log);
		createNamedDword(fpa, 0x1F8010C8L, "DMA_SPU_CHCR", log);
		
		createNamedDword(fpa, 0x1F8010D0L, "DMA_PIO_MADR", log);
		createNamedDword(fpa, 0x1F8010D4L, "DMA_PIO_BCR", log);
		createNamedDword(fpa, 0x1F8010D8L, "DMA_PIO_CHCR", log);
		
		createNamedDword(fpa, 0x1F8010E0L, "DMA_OTC_MADR", log);
		createNamedDword(fpa, 0x1F8010E4L, "DMA_OTC_BCR", log);
		createNamedDword(fpa, 0x1F8010E8L, "DMA_OTC_CHCR", log);
		
		createNamedDword(fpa, 0x1F8010F0L, "DMA_DPCR", log);
		createNamedDword(fpa, 0x1F8010F4L, "DMA_DICR", log);
	}
	
	private static void addTimers(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "TMR_DOTCLOCK", 0x1F801100L, 0x10, true, false, log);
		createSegment(fpa, null, "TMR_HRETRACE", 0x1F801110L, 0x10, true, false, log);
		createSegment(fpa, null, "TMR_SYSCLOCK", 0x1F801120L, 0x10, true, false, log);
		
		createNamedDword(fpa, 0x1F801100L, "TMR_DOTCLOCK_VAL", log);
		createNamedDword(fpa, 0x1F801104L, "TMR_DOTCLOCK_MODE", log);
		createNamedDword(fpa, 0x1F801108L, "TMR_DOTCLOCK_MAX", log);
		
		createNamedDword(fpa, 0x1F801110L, "TMR_HRETRACE_VAL", log);
		createNamedDword(fpa, 0x1F801114L, "TMR_HRETRACE_MODE", log);
		createNamedDword(fpa, 0x1F801118L, "TMR_HRETRACE_MAX", log);
		
		createNamedDword(fpa, 0x1F801120L, "TMR_SYSCLOCK_VAL", log);
		createNamedDword(fpa, 0x1F801124L, "TMR_SYSCLOCK_MODE", log);
		createNamedDword(fpa, 0x1F801128L, "TMR_SYSCLOCK_MAX", log);
	}
	
	private static void addCdromRegs(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "CDROM_REGS", 0x1F801800L, 4, true, false, log);
		
		createNamedByte(fpa, 0x1F801800L, "CDROM_REG0", log);
		createNamedByte(fpa, 0x1F801801L, "CDROM_REG1", log);
		createNamedByte(fpa, 0x1F801802L, "CDROM_REG2", log);
		createNamedByte(fpa, 0x1F801803L, "CDROM_REG3", log);
	}
	
	private static void addGpuRegs(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "GPU_REGS", 0x1F801810L, 8, true, false, log);
		
		createNamedDword(fpa, 0x1F801810L, "GPU_REG0", log);
		createNamedDword(fpa, 0x1F801814L, "GPU_REG1", log);
	}
	
	private static void addMdecRegs(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "MDEC_REGS", 0x1F801820L, 8, true, false, log);
		
		createNamedDword(fpa, 0x1F801820L, "MDEC_REG0", log);
		createNamedDword(fpa, 0x1F801824L, "MDEC_REG1", log);
	}
	
	private static void addSpuVoices(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "SPU_VOICES", 0x1F801C00L, 0x10 * 24, true, false, log);
		
		for (int i = 0; i < 24; ++i) {
			createNamedDword(fpa, 0x1F801C00L + i * 0x10, String.format("VOICE_%02x_LEFT_RIGHT", i), log);
			createNamedWord(fpa, 0x1F801C00L + i * 0x10 + 0x04, String.format("VOICE_%02x_ADPCM_SAMPLE_RATE", i), log);
			createNamedWord(fpa, 0x1F801C00L + i * 0x10 + 0x06, String.format("VOICE_%02x_ADPCM_START_ADDR", i), log);
			createNamedWord(fpa, 0x1F801C00L + i * 0x10 + 0x08, String.format("VOICE_%02x_ADSR_ATT_DEC_SUS_REL", i), log);
			createNamedWord(fpa, 0x1F801C00L + i * 0x10 + 0x0C, String.format("VOICE_%02x_ADSR_CURR_VOLUME", i), log);
			createNamedWord(fpa, 0x1F801C00L + i * 0x10 + 0x0E, String.format("VOICE_%02x_ADPCM_REPEAT_ADDR", i), log);
		}
	}
	
	private static void addSpuCtrlRegs(FlatProgramAPI fpa, MessageLog log) {
		createSegment(fpa, null, "SPU_CTRL_REGS", 0x1F801D80L, 0x40, true, false, log);
		
		createNamedWord(fpa, 0x1F801D80L, "SPU_MAIN_VOL_L", log);
		createNamedWord(fpa, 0x1F801D82L, "SPU_MAIN_VOL_R", log);
		createNamedWord(fpa, 0x1F801D84L, "SPU_REVERB_OUT_L", log);
		createNamedWord(fpa, 0x1F801D86L, "SPU_REVERB_OUT_R", log);
		createNamedDword(fpa, 0x1F801D88L, "SPU_VOICE_KEY_ON", log);
		createNamedDword(fpa, 0x1F801D8CL, "SPU_VOICE_KEY_OFF", log);
		createNamedDword(fpa, 0x1F801D90L, "SPU_VOICE_CHN_FM_MODE", log);
		createNamedDword(fpa, 0x1F801D94L, "SPU_VOICE_CHN_NOISE_MODE", log);
		createNamedDword(fpa, 0x1F801D98L, "SPU_VOICE_CHN_REVERB_MODE", log);
		createNamedDword(fpa, 0x1F801D9CL, "SPU_VOICE_CHN_ON_OFF_STATUS", log);
		createNamedWord(fpa, 0x1F801DA0L, "SPU_UNKN_1DA0", log);
		createNamedWord(fpa, 0x1F801DA2L, "SOUND_RAM_REVERB_WORK_ADDR", log);
		createNamedWord(fpa, 0x1F801DA4L, "SOUND_RAM_IRQ_ADDR", log);
		createNamedWord(fpa, 0x1F801DA6L, "SOUND_RAM_DATA_TRANSFER_ADDR", log);
		createNamedWord(fpa, 0x1F801DA8L, "SOUND_RAM_DATA_TRANSFER_FIFO", log);
		createNamedWord(fpa, 0x1F801DAAL, "SPU_CTRL_REG_CPUCNT", log);
		createNamedWord(fpa, 0x1F801DACL, "SOUND_RAM_DATA_TRANSTER_CTRL", log);
		createNamedWord(fpa, 0x1F801DAEL, "SPU_STATUS_REG_SPUSTAT", log);
		createNamedWord(fpa, 0x1F801DB0L, "CD_VOL_L", log);
		createNamedWord(fpa, 0x1F801DB2L, "CD_VOL_R", log);
		createNamedWord(fpa, 0x1F801DB4L, "EXT_VOL_L", log);
		createNamedWord(fpa, 0x1F801DB6L, "EXT_VOL_R", log);
		createNamedWord(fpa, 0x1F801DB8L, "CURR_MAIN_VOL_L", log);
		createNamedWord(fpa, 0x1F801DBAL, "CURR_MAIN_VOL_R", log);
		createNamedDword(fpa, 0x1F801DBCL, "SPU_UNKN_1DBC", log);
	}
	
	private static void createNamedPtr(FlatProgramAPI fpa, long addr, String name, MessageLog log) {
		try {
			DataType dt = new PointerDataType();
			DataUtilities.createData(fpa.getCurrentProgram(), fpa.toAddr(addr), dt, dt.getLength(), false, ClearDataMode.CLEAR_ALL_UNDEFINED_CONFLICT_DATA);
			fpa.getCurrentProgram().getSymbolTable().createLabel(fpa.toAddr(addr), name, SourceType.IMPORTED);
		} catch (CodeUnitInsertionException | InvalidInputException e) {
			log.appendException(e);
		}
	}
	
	private static void createNamedByte(FlatProgramAPI fpa, long address, String name, MessageLog log) {
		try {
			fpa.createByte(fpa.toAddr(address));
		} catch (Exception e) {
			log.appendException(e);
			return;
		}
		
		try {
			fpa.getCurrentProgram().getSymbolTable().createLabel(fpa.toAddr(address), name, SourceType.IMPORTED);
		} catch (InvalidInputException e) {
			log.appendException(e);
		}
}
	
	private static void createNamedWord(FlatProgramAPI fpa, long address, String name, MessageLog log) {
		try {
			fpa.createWord(fpa.toAddr(address));
		} catch (Exception e) {
			log.appendException(e);
			return;
		}
		
		try {
			fpa.getCurrentProgram().getSymbolTable().createLabel(fpa.toAddr(address), name, SourceType.IMPORTED);
		} catch (InvalidInputException e) {
			log.appendException(e);
		}
}
	
	private static void createNamedDword(FlatProgramAPI fpa, long address, String name, MessageLog log) {
		try {
			fpa.createDWord(fpa.toAddr(address));
		} catch (Exception e) {
			log.appendException(e);
			return;
		}
		
		try {
			fpa.getCurrentProgram().getSymbolTable().createLabel(fpa.toAddr(address), name, SourceType.IMPORTED);
		} catch (InvalidInputException e) {
			log.appendException(e);
		}
	}
	
	private static void createSegment(FlatProgramAPI fpa, InputStream stream, String name, long address, long size, boolean write, boolean execute, MessageLog log) {
		MemoryBlock block;
		try {
			block = fpa.createMemoryBlock(name, fpa.toAddr(address), stream, size, false);
			block.setRead(true);
			block.setWrite(write);
			block.setExecute(execute);
		} catch (Exception e) {
			log.appendException(e);
		}
	}
}

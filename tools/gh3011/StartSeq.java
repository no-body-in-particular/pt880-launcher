// Find the code that builds the chip's i2c transfers, via the ioctl call sites.
//
// Constant searches all failed, and the reason is now clear: 0xdddd and 0xaaaa appear nowhere in
// the binary, because the register is written as two separate bytes. The wire format is
// p[0]=reg>>8, p[1]=reg, so a register of 0xdddd only ever needs the byte 0xdd. There is no
// 16-bit constant to find.
//
// So work from the call sites instead. Everything reaches the chip through ioctl, so the
// functions that call it are the i2c helpers, and their callers are the sequence logic.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import java.util.LinkedHashSet;
import java.util.Set;

public class StartSeq extends GhidraScript {

    @Override
    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);

        // Every symbol called "ioctl", however Ghidra has named the thunk or PLT entry.
        Set<Address> targets = new LinkedHashSet<Address>();
        SymbolIterator si = currentProgram.getSymbolTable().getAllSymbols(true);
        while (si.hasNext()) {
            Symbol s = si.next();
            if (s.getName() != null && s.getName().contains("ioctl")) {
                targets.add(s.getAddress());
                println("ioctl symbol: " + s.getName() + " @ " + s.getAddress());
            }
        }

        // Direct callers of those.
        Set<Function> helpers = new LinkedHashSet<Function>();
        for (Address a : targets) {
            ReferenceIterator ri = currentProgram.getReferenceManager().getReferencesTo(a);
            while (ri.hasNext()) {
                Reference r = ri.next();
                Function f = getFunctionContaining(r.getFromAddress());
                if (f != null) helpers.add(f);
            }
        }
        println("");
        println("functions calling ioctl: " + helpers.size());
        for (Function f : helpers) println("   " + f.getName() + " @ " + f.getEntryPoint());

        // And their callers - the sequence logic sits one level up from the i2c helper.
        Set<Function> callers = new LinkedHashSet<Function>();
        for (Function h : helpers) {
            ReferenceIterator ri =
                    currentProgram.getReferenceManager().getReferencesTo(h.getEntryPoint());
            while (ri.hasNext()) {
                Function f = getFunctionContaining(ri.next().getFromAddress());
                if (f != null && !helpers.contains(f)) callers.add(f);
            }
        }
        println("");
        println("their callers: " + callers.size());
        for (Function f : callers) println("   " + f.getName() + " @ " + f.getEntryPoint());

        println("");
        int printed = 0;
        for (Function f : helpers) {
            if (printed >= 2) break;
            DecompileResults r = dec.decompileFunction(f, 40, monitor);
            if (!r.decompileCompleted()) continue;
            println("-------- helper: " + f.getName() + " @ " + f.getEntryPoint());
            println(r.getDecompiledFunction().getC());
            printed++;
        }
        printed = 0;
        for (Function f : callers) {
            if (printed >= 3) break;
            DecompileResults r = dec.decompileFunction(f, 40, monitor);
            if (!r.decompileCompleted()) continue;
            String c = r.getDecompiledFunction().getC();
            // Prefer callers that look like sequence logic rather than one-off probes.
            if (c.length() < 400) continue;
            println("-------- caller: " + f.getName() + " @ " + f.getEntryPoint());
            println(c);
            printed++;
        }

        dec.dispose();
    }
}

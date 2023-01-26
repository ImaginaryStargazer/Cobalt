/*
 * The MIT License (MIT)
 *
 * Original Source: Copyright (c) 2009-2011 Luaj.org. All rights reserved.
 * Modifications: Copyright (c) 2015-2020 SquidDev
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.squiddev.cobalt.compiler;

import org.squiddev.cobalt.*;
import org.squiddev.cobalt.compiler.LexState.ConsControl;
import org.squiddev.cobalt.compiler.LexState.expdesc;
import org.squiddev.cobalt.function.LocalVariable;

import java.util.HashMap;
import java.util.Map;

import static org.squiddev.cobalt.Constants.*;
import static org.squiddev.cobalt.Lua.*;
import static org.squiddev.cobalt.compiler.LuaC.*;

class FuncState {
	static class UpvalueDesc {
		ExpKind k;
		short info;
	}

	static class BlockCnt {
		BlockCnt previous;  /* chain */
		IntPtr breaklist = new IntPtr();  /* list of jumps out of this loop */
		short nactvar;  /* # active locals outside the breakable structure */
		boolean upval;  /* true if some variable in the block is an upvalue */
		boolean isbreakable;  /* true if `block' is a loop */
	}

	Prototype f;  /* current function header */
	//	LTable h;  /* table to find (and reuse) elements in `k' */
	final Map<LuaValue, Integer> htable = new HashMap<>();  /* table to find (and reuse) elements in `k' */
	FuncState prev;  /* enclosing function */
	LexState ls;  /* lexical state */
	BlockCnt bl;  /* chain of current blocks */
	int pc;  /* next position to code (equivalent to `ncode') */
	int lasttarget;   /* `pc' of last `jump target' */
	IntPtr jpc;  /* list of pending jumps to `pc' */
	int freereg;  /* first free register */
	int nk;  /* number of elements in `k' */
	int np;  /* number of elements in `p' */
	short nlocvars;  /* number of elements in `locvars' */
	short nactvar;  /* number of active local variables */
	UpvalueDesc[] upvalues = new UpvalueDesc[LUAI_MAXUPVALUES];  /* upvalues */
	short[] actvar = new short[LUAI_MAXVARS];  /* declared-variable stack */

	FuncState() {
	}


	// =============================================================
	// from lcode.h
	// =============================================================

	InstructionPtr getcodePtr(expdesc e) {
		return new InstructionPtr(f.code, e.u.s.info);
	}

	int getcode(expdesc e) {
		return f.code[e.u.s.info];
	}

	int codeAsBx(int o, int A, int sBx) throws CompileException {
		return codeABx(o, A, sBx + MAXARG_sBx);
	}

	void setmultret(expdesc e) throws CompileException {
		setreturns(e, LUA_MULTRET);
	}


	// =============================================================
	// from lparser.c
	// =============================================================

	LocalVariable getlocvar(int i) {
		return f.locvars[actvar[i]];
	}

	void checklimit(int v, int l, String msg) throws CompileException {
		if (v > l) {
			errorlimit(l, msg);
		}
	}

	private void errorlimit(int limit, String what) throws CompileException {
		String msg = (f.linedefined == 0) ?
			"main function has more than " + limit + " " + what :
			"function at line " + f.linedefined + " has more than " + limit + " " + what;
		throw ls.lexError(msg, 0);
	}


	private int indexupvalue(LuaString name, expdesc v) throws CompileException {
		int i;
		for (i = 0; i < f.nups; i++) {
			if (upvalues[i].k == v.kind && upvalues[i].info == v.u.s.info) {
				_assert(f.upvalues[i] == name);
				return i;
			}
		}
		/* new one */
		checklimit(f.nups + 1, LUAI_MAXUPVALUES, "upvalues");
		if (f.upvalues == null || f.nups + 1 > f.upvalues.length) {
			f.upvalues = realloc(f.upvalues, f.nups * 2 + 1);
		}
		f.upvalues[f.nups] = name;
		_assert(v.kind == ExpKind.VLOCAL || v.kind == ExpKind.VUPVAL);
		upvalues[f.nups] = new UpvalueDesc();
		upvalues[f.nups].k = v.kind;
		upvalues[f.nups].info = (short) (v.u.s.info);
		return f.nups++;
	}

	private int searchvar(LuaString n) {
		int i;
		for (i = nactvar - 1; i >= 0; i--) {
			if (n == getlocvar(i).name) {
				return i;
			}
		}
		return -1; /* not found */
	}

	private void markupval(int level) {
		BlockCnt bl = this.bl;
		while (bl != null && bl.nactvar > level) {
			bl = bl.previous;
		}
		if (bl != null) {
			bl.upval = true;
		}
	}

	ExpKind singlevaraux(LuaString n, expdesc var, int base) throws CompileException {
		int v = searchvar(n); /* look up at current level */
		if (v >= 0) {
			var.init(ExpKind.VLOCAL, v);
			if (base == 0) {
				markupval(v); /* local will be used as an upval */
			}
			return ExpKind.VLOCAL;
		} else { /* not found at current level; try upper one */
			if (prev == null) { /* no more levels? */
				/* default is global variable */
				var.init(ExpKind.VGLOBAL, NO_REG);
				return ExpKind.VGLOBAL;
			}
			if (prev.singlevaraux(n, var, 0) == ExpKind.VGLOBAL) {
				return ExpKind.VGLOBAL;
			}
			var.u.s.info = indexupvalue(n, var); /* else was LOCAL or UPVAL */
			var.kind = ExpKind.VUPVAL; /* upvalue in this level */
			return ExpKind.VUPVAL;
		}
	}

	void enterblock(BlockCnt bl, boolean isbreakable) throws CompileException {
		bl.breaklist.value = LexState.NO_JUMP;
		bl.isbreakable = isbreakable;
		bl.nactvar = this.nactvar;
		bl.upval = false;
		bl.previous = this.bl;
		this.bl = bl;
		_assert(this.freereg == this.nactvar);
	}

	//
//	void leaveblock (FuncState *fs) {
//	  BlockCnt *bl = this.bl;
//	  this.bl = bl.previous;
//	  removevars(this.ls, bl.nactvar);
//	  if (bl.upval)
//	    this.codeABC(OP_CLOSE, bl.nactvar, 0, 0);
//	  /* a block either controls scope or breaks (never both) */
//	  assert(!bl.isbreakable || !bl.upval);
//	  assert(bl.nactvar == this.nactvar);
//	  this.freereg = this.nactvar;  /* free registers */
//	  this.patchtohere(bl.breaklist);
//	}

	void leaveblock() throws CompileException {
		BlockCnt bl = this.bl;
		this.bl = bl.previous;
		ls.removevars(bl.nactvar);
		if (bl.upval) {
			this.codeABC(OP_CLOSE, bl.nactvar, 0, 0);
		}
		/* a block either controls scope or breaks (never both) */
		_assert(!bl.isbreakable || !bl.upval);
		_assert(bl.nactvar == this.nactvar);
		this.freereg = this.nactvar; /* free registers */
		this.patchtohere(bl.breaklist.value);
	}

	void closelistfield(ConsControl cc) throws CompileException {
		if (cc.v.kind == ExpKind.VVOID) {
			return; /* there is no list item */
		}
		this.exp2nextreg(cc.v);
		cc.v.kind = ExpKind.VVOID;
		if (cc.tostore == LFIELDS_PER_FLUSH) {
			this.setlist(cc.t.u.s.info, cc.na, cc.tostore); /* flush */
			cc.tostore = 0; /* no more items pending */
		}
	}

	void lastlistfield(ConsControl cc) throws CompileException {
		if (cc.tostore == 0) return;
		if (cc.v.kind.hasMultiRet()) {
			this.setmultret(cc.v);
			this.setlist(cc.t.u.s.info, cc.na, LUA_MULTRET);
			cc.na--;  /* do not count last expression (unknown number of elements) */
		} else {
			if (cc.v.kind != ExpKind.VVOID) {
				this.exp2nextreg(cc.v);
			}
			this.setlist(cc.t.u.s.info, cc.na, cc.tostore);
		}
	}


	// =============================================================
	// from lcode.c
	// =============================================================

	void nil(int from, int n) throws CompileException {
		InstructionPtr previous;
		if (this.pc > this.lasttarget) { /* no jumps to current position? */
			if (this.pc == 0) { /* function start? */
				if (from >= this.nactvar) {
					return; /* positions are already clean */
				}
			} else {
				previous = new InstructionPtr(this.f.code, this.pc - 1);
				if (GET_OPCODE(previous.get()) == OP_LOADNIL) {
					int pfrom = GETARG_A(previous.get());
					int pto = GETARG_B(previous.get());
					if (pfrom <= from && from <= pto + 1) { /* can connect both? */
						if (from + n - 1 > pto) {
							SETARG_B(previous, from + n - 1);
						}
						return;
					}
				}
			}
		}
		/* else no optimization */
		this.codeABC(OP_LOADNIL, from, from + n - 1, 0);
	}


	int jump() throws CompileException {
		int jpc = this.jpc.value; /* save list of jumps to here */
		this.jpc.value = LexState.NO_JUMP;
		IntPtr j = new IntPtr(this.codeAsBx(OP_JMP, 0, LexState.NO_JUMP));
		this.concat(j, jpc); /* keep them on hold */
		return j.value;
	}

	void ret(int first, int nret) throws CompileException {
		this.codeABC(OP_RETURN, first, nret + 1, 0);
	}

	private int condjump(int /* OpCode */op, int A, int B, int C) throws CompileException {
		this.codeABC(op, A, B, C);
		return this.jump();
	}

	private void fixjump(int pc, int dest) throws CompileException {
		InstructionPtr jmp = new InstructionPtr(this.f.code, pc);
		int offset = dest - (pc + 1);
		_assert(dest != LexState.NO_JUMP);
		if (Math.abs(offset) > MAXARG_sBx) {
			throw ls.syntaxError("control structure too long");
		}
		SETARG_sBx(jmp, offset);
	}


	/*
	 * * returns current `pc' and marks it as a jump target (to avoid wrong *
	 * optimizations with consecutive instructions not in the same basic block).
	 */
	int getlabel() {
		this.lasttarget = this.pc;
		return this.pc;
	}


	private int getjump(int pc) {
		int offset = GETARG_sBx(this.f.code[pc]);
		/* point to itself represents end of list */
		if (offset == LexState.NO_JUMP)
			/* end of list */ {
			return LexState.NO_JUMP;
		} else
			/* turn offset into absolute position */ {
			return (pc + 1) + offset;
		}
	}


	private InstructionPtr getjumpcontrol(int pc) {
		InstructionPtr pi = new InstructionPtr(this.f.code, pc);
		if (pc >= 1 && testTMode(GET_OPCODE(pi.code[pi.idx - 1]))) {
			return new InstructionPtr(pi.code, pi.idx - 1);
		} else {
			return pi;
		}
	}


	/*
	 * * check whether list has any jump that do not produce a value * (or
	 * produce an inverted value)
	 */
	private boolean need_value(int list) {
		for (; list != LexState.NO_JUMP; list = this.getjump(list)) {
			int i = this.getjumpcontrol(list).get();
			if (GET_OPCODE(i) != OP_TESTSET) {
				return true;
			}
		}
		return false; /* not found */
	}


	private boolean patchtestreg(int node, int reg) {
		InstructionPtr i = this.getjumpcontrol(node);
		if (GET_OPCODE(i.get()) != OP_TESTSET)
			/* cannot patch other instructions */ {
			return false;
		}
		if (reg != NO_REG && reg != GETARG_B(i.get())) {
			SETARG_A(i, reg);
		} else
			/* no register to put value or register already has the value */ {
			i.set(CREATE_ABC(OP_TEST, GETARG_B(i.get()), 0, Lua.GETARG_C(i.get())));
		}

		return true;
	}


	private void removevalues(int list) {
		for (; list != LexState.NO_JUMP; list = this.getjump(list)) {
			this.patchtestreg(list, NO_REG);
		}
	}

	private void patchlistaux(int list, int vtarget, int reg, int dtarget) throws CompileException {
		while (list != LexState.NO_JUMP) {
			int next = this.getjump(list);
			if (this.patchtestreg(list, reg)) {
				this.fixjump(list, vtarget);
			} else {
				this.fixjump(list, dtarget); /* jump to default target */
			}
			list = next;
		}
	}

	private void dischargejpc() throws CompileException {
		this.patchlistaux(this.jpc.value, this.pc, NO_REG, this.pc);
		this.jpc.value = LexState.NO_JUMP;
	}

	void patchlist(int list, int target) throws CompileException {
		if (target == this.pc) {
			this.patchtohere(list);
		} else {
			_assert(target < this.pc);
			this.patchlistaux(list, target, NO_REG, target);
		}
	}

	void patchtohere(int list) throws CompileException {
		this.getlabel();
		this.concat(this.jpc, list);
	}

	void concat(IntPtr l1, int l2) throws CompileException {
		if (l2 == LexState.NO_JUMP) {
			return;
		}
		if (l1.value == LexState.NO_JUMP) {
			l1.value = l2;
		} else {
			int list = l1.value;
			int next;
			while ((next = this.getjump(list)) != LexState.NO_JUMP)
				/* find last element */ {
				list = next;
			}
			this.fixjump(list, l2);
		}
	}

	void checkstack(int n) throws CompileException {
		int newstack = this.freereg + n;
		if (newstack > this.f.maxstacksize) {
			if (newstack >= MAXSTACK) {
				throw ls.syntaxError("function or expression too complex");
			}
			this.f.maxstacksize = newstack;
		}
	}

	void reserveregs(int n) throws CompileException {
		this.checkstack(n);
		this.freereg += n;
	}

	private void freereg(int reg) throws CompileException {
		if (!ISK(reg) && reg >= this.nactvar) {
			this.freereg--;
			_assert(reg == this.freereg);
		}
	}

	private void freeexp(expdesc e) throws CompileException {
		if (e.kind == ExpKind.VNONRELOC) {
			this.freereg(e.u.s.info);
		}
	}

	private int addk(LuaValue v) {
		int idx;
		if (this.htable.containsKey(v)) {
			idx = htable.get(v);
		} else {
			idx = this.nk;
			this.htable.put(v, idx);
			final Prototype f = this.f;
			if (f.k == null || nk + 1 >= f.k.length) {
				f.k = realloc(f.k, nk * 2 + 1);
			}
			f.k[this.nk++] = v;
		}
		return idx;
	}

	int stringK(LuaString s) {
		return this.addk(s);
	}

	int numberK(LuaValue r) {
		if (r instanceof LuaDouble) {
			double d = r.toDouble();
			int i = (int) d;
			if (d == (double) i) {
				r = LuaInteger.valueOf(i);
			}
		}
		return this.addk(r);
	}

	private int boolK(boolean b) {
		return this.addk((b ? TRUE : FALSE));
	}

	private int nilK() {
		return this.addk(NIL);
	}

	void setreturns(expdesc e, int nresults) throws CompileException {
		if (e.kind == ExpKind.VCALL) { /* expression is an open function call? */
			SETARG_C(this.getcodePtr(e), nresults + 1);
		} else if (e.kind == ExpKind.VVARARG) {
			SETARG_B(this.getcodePtr(e), nresults + 1);
			SETARG_A(this.getcodePtr(e), this.freereg);
			this.reserveregs(1);
		}
	}

	void setoneret(expdesc e) {
		if (e.kind == ExpKind.VCALL) { /* expression is an open function call? */
			e.kind = ExpKind.VNONRELOC;
			e.u.s.info = GETARG_A(this.getcode(e));
		} else if (e.kind == ExpKind.VVARARG) {
			SETARG_B(this.getcodePtr(e), 2);
			e.kind = ExpKind.VRELOCABLE; /* can relocate its simple result */
		}
	}

	void dischargevars(expdesc e) throws CompileException {
		switch (e.kind) {
			case VLOCAL: {
				e.kind = ExpKind.VNONRELOC;
				break;
			}
			case VUPVAL: {
				e.u.s.info = this.codeABC(OP_GETUPVAL, 0, e.u.s.info, 0);
				e.kind = ExpKind.VRELOCABLE;
				break;
			}
			case VGLOBAL: {
				e.u.s.info = this.codeABx(OP_GETGLOBAL, 0, e.u.s.info);
				e.kind = ExpKind.VRELOCABLE;
				break;
			}
			case VINDEXED: {
				this.freereg(e.u.s.aux);
				this.freereg(e.u.s.info);
				e.u.s.info = this
					.codeABC(OP_GETTABLE, 0, e.u.s.info, e.u.s.aux);
				e.kind = ExpKind.VRELOCABLE;
				break;
			}
			case VVARARG:
			case VCALL: {
				this.setoneret(e);
				break;
			}
			default:
				break; /* there is one value available (somewhere) */
		}
	}

	private int code_label(int A, int b, int jump) throws CompileException {
		this.getlabel(); /* those instructions may be jump targets */
		return this.codeABC(OP_LOADBOOL, A, b, jump);
	}

	private void discharge2reg(expdesc e, int reg) throws CompileException {
		this.dischargevars(e);
		switch (e.kind) {
			case VNIL: {
				this.nil(reg, 1);
				break;
			}
			case VFALSE:
			case VTRUE: {
				this.codeABC(OP_LOADBOOL, reg, (e.kind == ExpKind.VTRUE ? 1 : 0),
					0);
				break;
			}
			case VK: {
				this.codeABx(OP_LOADK, reg, e.u.s.info);
				break;
			}
			case VKNUM: {
				this.codeABx(OP_LOADK, reg, this.numberK(e.u.nval()));
				break;
			}
			case VRELOCABLE: {
				InstructionPtr pc = this.getcodePtr(e);
				SETARG_A(pc, reg);
				break;
			}
			case VNONRELOC: {
				if (reg != e.u.s.info) {
					this.codeABC(OP_MOVE, reg, e.u.s.info, 0);
				}
				break;
			}
			default: {
				_assert(e.kind == ExpKind.VVOID || e.kind == ExpKind.VJMP);
				return; /* nothing to do... */
			}
		}
		e.u.s.info = reg;
		e.kind = ExpKind.VNONRELOC;
	}

	private void discharge2anyreg(expdesc e) throws CompileException {
		if (e.kind != ExpKind.VNONRELOC) {
			this.reserveregs(1);
			this.discharge2reg(e, this.freereg - 1);
		}
	}

	private void exp2reg(expdesc e, int reg) throws CompileException {
		this.discharge2reg(e, reg);
		if (e.kind == ExpKind.VJMP) {
			this.concat(e.t, e.u.s.info); /* put this jump in `t' list */
		}
		if (e.hasjumps()) {
			int _final; /* position after whole expression */
			int p_f = LexState.NO_JUMP; /* position of an eventual LOAD false */
			int p_t = LexState.NO_JUMP; /* position of an eventual LOAD true */
			if (this.need_value(e.t.value) || this.need_value(e.f.value)) {
				int fj = (e.kind == ExpKind.VJMP) ? LexState.NO_JUMP : this
					.jump();
				p_f = this.code_label(reg, 0, 1);
				p_t = this.code_label(reg, 1, 0);
				this.patchtohere(fj);
			}
			_final = this.getlabel();
			this.patchlistaux(e.f.value, _final, reg, p_f);
			this.patchlistaux(e.t.value, _final, reg, p_t);
		}
		e.f.value = e.t.value = LexState.NO_JUMP;
		e.u.s.info = reg;
		e.kind = ExpKind.VNONRELOC;
	}

	void exp2nextreg(expdesc e) throws CompileException {
		this.dischargevars(e);
		this.freeexp(e);
		this.reserveregs(1);
		this.exp2reg(e, this.freereg - 1);
	}

	int exp2anyreg(expdesc e) throws CompileException {
		this.dischargevars(e);
		if (e.kind == ExpKind.VNONRELOC) {
			if (!e.hasjumps()) {
				return e.u.s.info; /* exp is already in a register */
			}
			if (e.u.s.info >= this.nactvar) { /* reg. is not a local? */
				this.exp2reg(e, e.u.s.info); /* put value on it */
				return e.u.s.info;
			}
		}
		this.exp2nextreg(e); /* default */
		return e.u.s.info;
	}

	void exp2val(expdesc e) throws CompileException {
		if (e.hasjumps()) {
			this.exp2anyreg(e);
		} else {
			this.dischargevars(e);
		}
	}

	int exp2RK(expdesc e) throws CompileException {
		this.exp2val(e);
		switch (e.kind) {
			case VKNUM:
			case VTRUE:
			case VFALSE:
			case VNIL: {
				if (this.nk <= MAXINDEXRK) { /* constant fit in RK operand? */
					e.u.s.info = (e.kind == ExpKind.VNIL) ? this.nilK()
						: (e.kind == ExpKind.VKNUM) ? this.numberK(e.u.nval())
						: this.boolK((e.kind == ExpKind.VTRUE));
					e.kind = ExpKind.VK;
					return RKASK(e.u.s.info);
				} else {
					break;
				}
			}
			case VK: {
				if (e.u.s.info <= MAXINDEXRK) /* constant fit in argC? */ {
					return RKASK(e.u.s.info);
				} else {
					break;
				}
			}
			default:
				break;
		}
		/* not a constant in the right range: put it in a register */
		return this.exp2anyreg(e);
	}

	void storevar(expdesc var, expdesc ex) throws CompileException {
		switch (var.kind) {
			case VLOCAL: {
				this.freeexp(ex);
				this.exp2reg(ex, var.u.s.info);
				return;
			}
			case VUPVAL: {
				int e = this.exp2anyreg(ex);
				this.codeABC(OP_SETUPVAL, e, var.u.s.info, 0);
				break;
			}
			case VGLOBAL: {
				int e = this.exp2anyreg(ex);
				this.codeABx(OP_SETGLOBAL, e, var.u.s.info);
				break;
			}
			case VINDEXED: {
				int e = this.exp2RK(ex);
				this.codeABC(OP_SETTABLE, var.u.s.info, var.u.s.aux, e);
				break;
			}
			default: {
				_assert(false); /* invalid var kind to store */
				break;
			}
		}
		this.freeexp(ex);
	}

	void self(expdesc e, expdesc key) throws CompileException {
		int func;
		this.exp2anyreg(e);
		this.freeexp(e);
		func = this.freereg;
		this.reserveregs(2);
		this.codeABC(OP_SELF, func, e.u.s.info, this.exp2RK(key));
		this.freeexp(key);
		e.u.s.info = func;
		e.kind = ExpKind.VNONRELOC;
	}

	private void invertjump(expdesc e) throws CompileException {
		InstructionPtr pc = this.getjumpcontrol(e.u.s.info);
		_assert(testTMode(GET_OPCODE(pc.get()))
			&& GET_OPCODE(pc.get()) != OP_TESTSET && Lua
			.GET_OPCODE(pc.get()) != OP_TEST);
		// SETARG_A(pc, !(GETARG_A(pc.get())));
		int a = GETARG_A(pc.get());
		int nota = (a != 0 ? 0 : 1);
		SETARG_A(pc, nota);
	}

	private int jumponcond(expdesc e, int cond) throws CompileException {
		if (e.kind == ExpKind.VRELOCABLE) {
			int ie = this.getcode(e);
			if (GET_OPCODE(ie) == OP_NOT) {
				this.pc--; /* remove previous OP_NOT */
				return this.condjump(OP_TEST, GETARG_B(ie), 0, (cond != 0 ? 0 : 1));
			}
			/* else go through */
		}
		this.discharge2anyreg(e);
		this.freeexp(e);
		return this.condjump(OP_TESTSET, NO_REG, e.u.s.info, cond);
	}

	void goiftrue(expdesc e) throws CompileException {
		int pc; /* pc of last jump */
		this.dischargevars(e);
		switch (e.kind) {
			case VK:
			case VKNUM:
			case VTRUE: {
				pc = LexState.NO_JUMP; /* always true; do nothing */
				break;
			}
			case VFALSE: {
				pc = this.jump(); /* always jump */
				break;
			}
			case VJMP: {
				this.invertjump(e);
				pc = e.u.s.info;
				break;
			}
			default: {
				pc = this.jumponcond(e, 0);
				break;
			}
		}
		this.concat(e.f, pc); /* insert last jump in `f' list */
		this.patchtohere(e.t.value);
		e.t.value = LexState.NO_JUMP;
	}

	private void goiffalse(expdesc e) throws CompileException {
		int pc; /* pc of last jump */
		this.dischargevars(e);
		switch (e.kind) {
			case VNIL:
			case VFALSE: {
				pc = LexState.NO_JUMP; /* always false; do nothing */
				break;
			}
			case VTRUE: {
				pc = this.jump(); /* always jump */
				break;
			}
			case VJMP: {
				pc = e.u.s.info;
				break;
			}
			default: {
				pc = this.jumponcond(e, 1);
				break;
			}
		}
		this.concat(e.t, pc); /* insert last jump in `t' list */
		this.patchtohere(e.f.value);
		e.f.value = LexState.NO_JUMP;
	}

	private void codenot(expdesc e) throws CompileException {
		this.dischargevars(e);
		switch (e.kind) {
			case VNIL:
			case VFALSE: {
				e.kind = ExpKind.VTRUE;
				break;
			}
			case VK:
			case VKNUM:
			case VTRUE: {
				e.kind = ExpKind.VFALSE;
				break;
			}
			case VJMP: {
				this.invertjump(e);
				break;
			}
			case VRELOCABLE:
			case VNONRELOC: {
				this.discharge2anyreg(e);
				this.freeexp(e);
				e.u.s.info = this.codeABC(OP_NOT, 0, e.u.s.info, 0);
				e.kind = ExpKind.VRELOCABLE;
				break;
			}
			default: {
				_assert(false); /* cannot happen */
				break;
			}
		}
		/* interchange true and false lists */
		{
			int temp = e.f.value;
			e.f.value = e.t.value;
			e.t.value = temp;
		}
		this.removevalues(e.f.value);
		this.removevalues(e.t.value);
	}

	void indexed(expdesc t, expdesc k) throws CompileException {
		t.u.s.aux = this.exp2RK(k);
		t.kind = ExpKind.VINDEXED;
	}

	private boolean constfolding(int op, expdesc e1, expdesc e2) throws CompileException {
		LuaValue v1, v2, r;
		if (!e1.isnumeral() || !e2.isnumeral()) {
			return false;
		}
		v1 = e1.u.nval();
		v2 = e2.u.nval();
		try {
			switch (op) {
				case OP_ADD:
					r = OperationHelper.add(null, v1, v2);
					break;
				case OP_SUB:
					r = OperationHelper.sub(null, v1, v2);
					break;
				case OP_MUL:
					r = OperationHelper.mul(null, v1, v2);
					break;
				case OP_DIV:
					r = OperationHelper.div(null, v1, v2);
					break;
				case OP_MOD:
					r = OperationHelper.mod(null, v1, v2);
					break;
				case OP_POW:
					r = OperationHelper.pow(null, v1, v2);
					break;
				case OP_UNM:
					r = OperationHelper.neg(null, v1);
					break;
				case OP_LEN:
					// r = v1.len();
					// break;
					return false; /* no constant folding for 'len' */
				default:
					_assert(false);
					r = null;
					break;
			}
		} catch (UnwindThrowable | LuaError e) {
			return false;
		}

		if (Double.isNaN(r.toDouble())) {
			return false; /* do not attempt to produce NaN */
		}
		e1.u.setNval(r);
		return true;
	}

	private void codearith(int op, expdesc e1, expdesc e2) throws CompileException {
		if (constfolding(op, e1, e2)) {
		} else {
			int o2 = (op != OP_UNM && op != OP_LEN) ? this.exp2RK(e2)
				: 0;
			int o1 = this.exp2RK(e1);
			if (o1 > o2) {
				this.freeexp(e1);
				this.freeexp(e2);
			} else {
				this.freeexp(e2);
				this.freeexp(e1);
			}
			e1.u.s.info = this.codeABC(op, 0, o1, o2);
			e1.kind = ExpKind.VRELOCABLE;
		}
	}

	private void codecomp(int /* OpCode */op, int cond, expdesc e1, expdesc e2) throws CompileException {
		int o1 = this.exp2RK(e1);
		int o2 = this.exp2RK(e2);
		this.freeexp(e2);
		this.freeexp(e1);
		if (cond == 0 && op != OP_EQ) {
			int temp; /* exchange args to replace by `<' or `<=' */
			temp = o1;
			o1 = o2;
			o2 = temp; /* o1 <==> o2 */
			cond = 1;
		}
		e1.u.s.info = this.condjump(op, cond, o1, o2);
		e1.kind = ExpKind.VJMP;
	}

	void prefix(int /* UnOpr */op, expdesc e) throws CompileException {
		expdesc e2 = new expdesc();
		e2.init(ExpKind.VKNUM, 0);
		switch (op) {
			case LexState.OPR_MINUS: {
				if (e.kind == ExpKind.VK) {
					this.exp2anyreg(e); /* cannot operate on non-numeric constants */
				}
				this.codearith(OP_UNM, e, e2);
				break;
			}
			case LexState.OPR_NOT:
				this.codenot(e);
				break;
			case LexState.OPR_LEN: {
				this.exp2anyreg(e); /* cannot operate on constants */
				this.codearith(OP_LEN, e, e2);
				break;
			}
			default:
				_assert(false);
		}
	}

	void infix(int /* BinOpr */op, expdesc v) throws CompileException {
		switch (op) {
			case LexState.OPR_AND: {
				this.goiftrue(v);
				break;
			}
			case LexState.OPR_OR: {
				this.goiffalse(v);
				break;
			}
			case LexState.OPR_CONCAT: {
				this.exp2nextreg(v); /* operand must be on the `stack' */
				break;
			}
			case LexState.OPR_ADD:
			case LexState.OPR_SUB:
			case LexState.OPR_MUL:
			case LexState.OPR_DIV:
			case LexState.OPR_MOD:
			case LexState.OPR_POW: {
				if (!v.isnumeral()) {
					this.exp2RK(v);
				}
				break;
			}
			default: {
				this.exp2RK(v);
				break;
			}
		}
	}


	void posfix(int op, expdesc e1, expdesc e2) throws CompileException {
		switch (op) {
			case LexState.OPR_AND: {
				_assert(e1.t.value == LexState.NO_JUMP); /* list must be closed */
				this.dischargevars(e2);
				this.concat(e2.f, e1.f.value);
				// *e1 = *e2;
				e1.setvalue(e2);
				break;
			}
			case LexState.OPR_OR: {
				_assert(e1.f.value == LexState.NO_JUMP); /* list must be closed */
				this.dischargevars(e2);
				this.concat(e2.t, e1.t.value);
				// *e1 = *e2;
				e1.setvalue(e2);
				break;
			}
			case LexState.OPR_CONCAT: {
				this.exp2val(e2);
				if (e2.kind == ExpKind.VRELOCABLE
					&& GET_OPCODE(this.getcode(e2)) == OP_CONCAT) {
					_assert(e1.u.s.info == GETARG_B(this.getcode(e2)) - 1);
					this.freeexp(e1);
					SETARG_B(this.getcodePtr(e2), e1.u.s.info);
					e1.kind = ExpKind.VRELOCABLE;
					e1.u.s.info = e2.u.s.info;
				} else {
					this.exp2nextreg(e2); /* operand must be on the 'stack' */
					this.codearith(OP_CONCAT, e1, e2);
				}
				break;
			}
			case LexState.OPR_ADD:
				this.codearith(OP_ADD, e1, e2);
				break;
			case LexState.OPR_SUB:
				this.codearith(OP_SUB, e1, e2);
				break;
			case LexState.OPR_MUL:
				this.codearith(OP_MUL, e1, e2);
				break;
			case LexState.OPR_DIV:
				this.codearith(OP_DIV, e1, e2);
				break;
			case LexState.OPR_MOD:
				this.codearith(OP_MOD, e1, e2);
				break;
			case LexState.OPR_POW:
				this.codearith(OP_POW, e1, e2);
				break;
			case LexState.OPR_EQ:
				this.codecomp(OP_EQ, 1, e1, e2);
				break;
			case LexState.OPR_NE:
				this.codecomp(OP_EQ, 0, e1, e2);
				break;
			case LexState.OPR_LT:
				this.codecomp(OP_LT, 1, e1, e2);
				break;
			case LexState.OPR_LE:
				this.codecomp(OP_LE, 1, e1, e2);
				break;
			case LexState.OPR_GT:
				this.codecomp(OP_LT, 0, e1, e2);
				break;
			case LexState.OPR_GE:
				this.codecomp(OP_LE, 0, e1, e2);
				break;
			default:
				_assert(false);
		}
	}


	void fixline(int line) {
		this.f.lineinfo[this.pc - 1] = line;
	}


	private int code(int instruction, int line) throws CompileException {
		Prototype f = this.f;
		this.dischargejpc(); /* `pc' will change */
		/* put new instruction in code array */
		if (f.code == null || this.pc + 1 > f.code.length) {
			f.code = LuaC.realloc(f.code, this.pc * 2 + 1);
		}
		f.code[this.pc] = instruction;
		/* save corresponding line information */
		if (f.lineinfo == null || this.pc + 1 > f.lineinfo.length) {
			f.lineinfo = LuaC.realloc(f.lineinfo,
				this.pc * 2 + 1);
		}
		f.lineinfo[this.pc] = line;
		return this.pc++;
	}


	int codeABC(int o, int a, int b, int c) throws CompileException {
		_assert(getOpMode(o) == iABC);
		_assert(getBMode(o) != OpArgN || b == 0);
		_assert(getCMode(o) != OpArgN || c == 0);
		return this.code(CREATE_ABC(o, a, b, c), this.ls.lastline);
	}


	int codeABx(int o, int a, int bc) throws CompileException {
		_assert(getOpMode(o) == iABx || getOpMode(o) == iAsBx);
		_assert(getCMode(o) == OpArgN);
		return this.code(CREATE_ABx(o, a, bc), this.ls.lastline);
	}


	private void setlist(int base, int nelems, int tostore) throws CompileException {
		int c = (nelems - 1) / LFIELDS_PER_FLUSH + 1;
		int b = (tostore == LUA_MULTRET) ? 0 : tostore;
		_assert(tostore != 0);
		if (c <= MAXARG_C) {
			this.codeABC(OP_SETLIST, base, b, c);
		} else {
			this.codeABC(OP_SETLIST, base, b, 0);
			this.code(c, this.ls.lastline);
		}
		this.freereg = base + 1; /* free registers with list values */
	}
}

package com.plebcity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class CofferMessageParserTest
{
	@Test
	public void deposit()
	{
		CofferMessageParser.CofferEvent e = CofferMessageParser.parse("ToivoM has deposited 1,000,000 coins into the coffer.");
		assertEquals(CofferMessageParser.Type.DEPOSIT, e.getType());
		assertEquals("ToivoM", e.getPlayer());
		assertEquals(1_000_000L, e.getAmount());
	}

	@Test
	public void withdraw()
	{
		CofferMessageParser.CofferEvent e = CofferMessageParser.parse("ToivoM has withdrawn 1,000,000 coins from the coffer.");
		assertEquals(CofferMessageParser.Type.WITHDRAW, e.getType());
		assertEquals("ToivoM", e.getPlayer());
		assertEquals(1_000_000L, e.getAmount());
	}

	@Test
	public void nonBreakingSpaceInName()
	{
		CofferMessageParser.CofferEvent e = CofferMessageParser.parse("Pleb Man has deposited 250,000 coins into the coffer.");
		assertEquals(CofferMessageParser.Type.DEPOSIT, e.getType());
		assertEquals("Pleb Man", e.getPlayer());
		assertEquals(250_000L, e.getAmount());
	}

	@Test
	public void tagsAreRemoved()
	{
		CofferMessageParser.CofferEvent e = CofferMessageParser.parse("<col=ef1020>Pleb Man has withdrawn 5,000 coins from the coffer.</col>");
		assertEquals(CofferMessageParser.Type.WITHDRAW, e.getType());
		assertEquals("Pleb Man", e.getPlayer());
		assertEquals(5_000L, e.getAmount());
	}

	@Test
	public void singleCoin()
	{
		CofferMessageParser.CofferEvent e = CofferMessageParser.parse("ToivoM has deposited 1 coin into the coffer.");
		assertEquals(1L, e.getAmount());
	}

	@Test
	public void nonMatching()
	{
		assertNull(CofferMessageParser.parse("ToivoM has joined the clan."));
		assertNull(CofferMessageParser.parse("ToivoM has deposited lots of coins into the coffer."));
		assertNull(CofferMessageParser.parse("ToivoM has deposited 1,000 coins into the coffer"));
		assertNull(CofferMessageParser.parse("ToivoM has deposited 0 coins into the coffer."));
		assertNull(CofferMessageParser.parse("ToivoM has deposited , coins into the coffer."));
		assertNull(CofferMessageParser.parse("ToivoM has deposited 99,999,999,999,999,999,999 coins into the coffer."));
		assertNull(CofferMessageParser.parse(null));
	}
}

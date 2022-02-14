/*
  Copyright 2022 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.rulesengine;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.*;

public class MustacheTokenTests {

	private ConditionEvaluator defaultEvaluator = new ConditionEvaluator(ConditionEvaluator.Option.DEFAULT);

	@Before()
	public void setup() { }

	@Test
	public void Operand_MustacheToken() {
		// setup
		final OperandMustacheToken<String> tokenString = new OperandMustacheToken("{{Hero}}");
		final OperandMustacheToken<Number> tokenInteger = new OperandMustacheToken("{{integerToken}}");
		final OperandMustacheToken<Boolean> tokenBoolean = new OperandMustacheToken("{{booleanToken}}");

		// test
		final String result1 = tokenString.resolve(defaultContext(defaultEvaluator));
		final Number result2 = tokenInteger.resolve(defaultContext(defaultEvaluator));
		final Boolean result3 = tokenBoolean.resolve(defaultContext(defaultEvaluator));

		// verify
		assertEquals("Soldier", result1);
		assertEquals(33, result2);
		assertEquals(false, result3);
	}

	@Test
	public void Operand_MustacheToken_multipleToken() {
		// setup
		final OperandMustacheToken<String> operand = new OperandMustacheToken("{{Beer}}{{Corona}}");

		// test
		final Object result = operand.resolve(defaultContext(defaultEvaluator));

		// verify that operand mustache token resolves only the first token
		assertEquals("Corona", result);
	}

	@Test
	public void Operand_MustacheToken_InvalidToken() {
		// setup
		final OperandMustacheToken<String> example1 = new OperandMustacheToken("{{Beer");
		final OperandMustacheToken<String> example2 = new OperandMustacheToken("Beer{{Hero}}");
		final OperandMustacheToken<String> example3 = new OperandMustacheToken("onlyString");
		final OperandMustacheToken<String> example4 = new OperandMustacheToken("");
		final OperandMustacheToken<String> example5 = new OperandMustacheToken(null);

		// test and verify
		assertNull(example1.resolve(defaultContext(defaultEvaluator)));
		assertNull(example2.resolve(defaultContext(defaultEvaluator)));
		assertNull(example3.resolve(defaultContext(defaultEvaluator)));
		assertNull(example4.resolve(defaultContext(defaultEvaluator)));
		assertNull(example5.resolve(defaultContext(defaultEvaluator)));
	}

	@Test
	public void Operand_WrongDataType_Token() {
		// setup
		final OperandMustacheToken<String> wrongToken = new OperandMustacheToken("{{booleanToken}}");

		// test
		final Object result1 = wrongToken.resolve(defaultContext(defaultEvaluator));

		// verify
		assertTrue(result1 instanceof Boolean);
	}

	@Test
	public void testComparisonExpression_MustacheToken() {
		// setup
		final Operand lhs = new OperandMustacheToken("{{Hero}}");
		final Operand rhs = new OperandLiteral("Soldier");

		// test
		final ComparisonExpression expression = new ComparisonExpression(lhs, "equals", rhs);

		// verify
		final RulesResult result = expression.evaluate(defaultContext(defaultEvaluator));
		assertTrue(result.isSuccess());
	}

	@Test
	public void testComparisonExpression_MustacheFunction() {
		// setup
		final Operand lhs = new OperandMustacheToken("{{addExtraString(Beer)}}");
		final Operand rhs = new OperandLiteral("Corona extra");

		// test
		final ComparisonExpression expression = new ComparisonExpression(lhs, "equals", rhs);

		// verify
		final RulesResult result = expression.evaluate(defaultContext(defaultEvaluator));
		assertTrue(result.isSuccess());
	}

	@Test
	public void testComparisonExpression_MustacheFunction_and_Token() {
		// setup
		final Operand lhs = new OperandMustacheToken("{{addExtraString(Beer)}}");
		final Operand rhs = new OperandMustacheToken("{{answer}}");

		// test
		final ComparisonExpression expression = new ComparisonExpression(lhs, "equals", rhs);

		// verify
		final RulesResult result = expression.evaluate(defaultContext(defaultEvaluator));
		assertTrue(result.isSuccess());
	}


	/* **************************************************************************
	 *  Private methods
	 **************************************************************************/
	private Context defaultContext(final ConditionEvaluator conditionEvaluator) {
		HashMap<String, Object> context = new HashMap<String, Object>();
		context.put("Beer", "Corona");
		context.put("Hero", "Soldier");
		context.put("Soda", "Pepsi");
		context.put("answer", "Corona extra");
		context.put("integerToken", 33);
		context.put("booleanToken", false);
		return new Context(new FakeTokenFinder(context), conditionEvaluator, FakeTransformer.create());
	}
}

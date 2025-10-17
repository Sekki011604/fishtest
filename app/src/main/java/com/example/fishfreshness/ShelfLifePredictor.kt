package com.example.fishfreshness

import android.content.res.AssetManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

object ShelfLifePredictor {

	private val expectedParts = listOf("caudal_fin", "eye", "pectoral_fin", "skin_texture")
	private val categoryToNumeric = mapOf(
		"spoiled" to 0f,
		"less_fresh" to 1f,
		"fresh" to 2f,
		"very_fresh" to 3f
	)

	@Volatile
	private var labels: List<String> = emptyList()

	@Volatile
	private var labelToIndex: Map<String, Int> = emptyMap()

	private data class SplitNode(
		val splitFeature: Int,
		val threshold: Float,
		val yesId: Int,
		val noId: Int,
		val missingId: Int
	)

	private data class LeafNode(
		val value: Float
	)

	private data class Tree(
		val nodesById: Map<Int, Any>
	)

	private data class ArrayTree(
		val leftChildren: IntArray,
		val rightChildren: IntArray,
		val splitIndices: IntArray,
		val splitConditions: FloatArray,
		val defaultLeft: BooleanArray,
		val nodeTypes: IntArray, // 0: split, 1: leaf
		val leafValues: FloatArray,
		val leafIndexByNode: IntArray
	)

	@Volatile
	private var trees: List<Any>? = null // Tree or ArrayTree

	@Volatile
	private var baseScore: Float = 0f

	@Volatile
	private var featureCount: Int = 0

	fun init(assets: AssetManager) {
		if (trees != null) return
		try {
			val jsonText = assets.open("xgboost_fish_model.json").use { it.readBytes().toString(Charset.forName("UTF-8")) }
			parseXGBoostModel(JSONObject(jsonText))
			// Load labels to map detected class names to feature indices
			labels = assets.open("labels.txt").use { it.readBytes().toString(Charset.forName("UTF-8")) }
				.split('\n')
				.map { it.trim() }
				.filter { it.isNotEmpty() }
			labelToIndex = labels.mapIndexed { idx, s -> s to idx }.toMap()
			// Ensure feature vector can cover either JSON-declared features or label count
			featureCount = maxOf(featureCount, labels.size)
		} catch (_: Exception) {
			trees = emptyList()
			baseScore = 0f
			featureCount = 0
			labels = emptyList()
			labelToIndex = emptyMap()
		}
	}

	fun close() {
		trees = null
		baseScore = 0f
		featureCount = 0
	}

	fun predictShelfLife(detectedLabels: List<String>): String {
		Log.d("ShelfLifePredictor", "=== PREDICT START ===")
		Log.d("ShelfLifePredictor", "Input detectedLabels: $detectedLabels")
		
		val cleanLabels = detectedLabels.map { it.split(" ")[0] }
		Log.d("ShelfLifePredictor", "Clean labels: $cleanLabels")
		
		
		// Since XGBoost JSON doesn't have tree structure, use direct mapping
		val minutes = fallbackMinutesFromCategories(cleanLabels)
		
		Log.d("ShelfLifePredictor", "Final minutes: $minutes")
		Log.d("ShelfLifePredictor", "=== PREDICT END ===")
		return formatShelfLife(minutes)
	}

	// Robustly extract freshness category from a YOLO label regardless of order/case/format
	private fun extractCategory(rawLabel: String): String {
		val s = rawLabel.trim().lowercase().replace('-', '_')
		return when {
			s.contains("very_fresh") -> "very_fresh"
			s.contains("less_fresh") -> "less_fresh"
			s.contains("fresh") -> "fresh"
			s.contains("spoiled") -> "spoiled"
			else -> "spoiled"
		}
	}
	private fun fallbackMinutesFromCategories(cleanLabels: List<String>): Int {
		val categoryToHours = mapOf(
			"very_fresh" to 24,   // 24 hours
			"fresh" to 18,        // 18 hours
			"less_fresh" to 8,    // 8 hours
			"spoiled" to 0        // 0 hours - don't eat
		)
		var minHours: Int? = null
		var detectedCount = 0
		Log.d("ShelfLifePredictor", "=== FALLBACK DEBUG ===")
		Log.d("ShelfLifePredictor", "Input cleanLabels: $cleanLabels")
		Log.d("ShelfLifePredictor", "cleanLabels size: ${cleanLabels.size}")
		Log.d("ShelfLifePredictor", "categoryToHours: $categoryToHours")
		
		// Process actually detected labels (order-independent parsing)
		for (i in cleanLabels.indices) {
			val label = cleanLabels[i]
			Log.d("ShelfLifePredictor", "Processing label $i: '$label'")
			val category = extractCategory(label)
			val hours = categoryToHours[category] ?: 2
			detectedCount++
			Log.d("ShelfLifePredictor", "Label: '$label' -> Category: '$category' -> Hours: $hours")
			minHours = if (minHours == null) hours else kotlin.math.min(minHours!!, hours)
		}
		
		// If no detections at all, use conservative default
		val finalHours = minHours ?: 12
		Log.d("ShelfLifePredictor", "=== FINAL RESULT ===")
		Log.d("ShelfLifePredictor", "Final hours: $finalHours, detected count: $detectedCount")
		Log.d("ShelfLifePredictor", "Final minutes: ${finalHours * 60}")
		
		// If no detections found, return a reasonable default based on image analysis
		if (detectedCount == 0) {
			Log.d("ShelfLifePredictor", "No detections found, using default 8 hours")
			return 8 * 60 // 8 hours default
		}
		
		return finalHours * 60 // convert to minutes
	}

	private fun evaluateTree(tree: Any, features: FloatArray): Float {
		if (tree is Tree) {
			var currentId = tree.nodesById.keys.minOrNull() ?: 0
			while (true) {
				val node = tree.nodesById[currentId] ?: return 0f
				when (node) {
					is LeafNode -> return node.value
					is SplitNode -> {
						val fval = features.getOrElse(node.splitFeature) { 0f }
						currentId = if (fval.isNaN()) node.missingId else if (fval < node.threshold) node.yesId else node.noId
					}
					else -> return 0f
				}
			}
		}
		if (tree is ArrayTree) {
			var nodeId = 0
			while (true) {
				val type = tree.nodeTypes.getOrElse(nodeId) { 1 }
				if (type == 1) {
					val leafIndex = tree.leafIndexByNode.getOrElse(nodeId) { -1 }
					return if (leafIndex >= 0) tree.leafValues.getOrElse(leafIndex) { 0f } else 0f
				}
				val splitIndex = tree.splitIndices.getOrElse(nodeId) { 0 }
				val threshold = tree.splitConditions.getOrElse(nodeId) { 0f }
				val fval = features.getOrElse(splitIndex) { 0f }
				val goLeft = if (fval.isNaN()) tree.defaultLeft.getOrElse(nodeId) { true } else fval < threshold
				nodeId = if (goLeft) tree.leftChildren.getOrElse(nodeId) { -1 } else tree.rightChildren.getOrElse(nodeId) { -1 }
				if (nodeId < 0) return 0f
			}
		}
		return 0f
	}

	private fun parseXGBoostModel(root: JSONObject) {
		val learner = root.getJSONObject("learner")
		baseScore = try {
			// Try multiple locations for base_score across XGBoost versions
			val attr = learner.optJSONObject("attributes")
			val modelParam = learner.optJSONObject("learner_model_param")
			when {
				attr != null && attr.has("base_score") -> attr.getString("base_score").toFloat()
				modelParam != null && modelParam.has("base_score") -> modelParam.getString("base_score").toFloat()
				learner.has("base_score") -> learner.getDouble("base_score").toFloat()
				else -> 0f
			}
		} catch (_: Exception) { 0f }
		val featureNames = learner.optJSONArray("feature_names") ?: JSONArray()
		featureCount = featureNames.length()
		
		// This JSON format doesn't have tree structure, so we'll use fallback only
		Log.d("ShelfLifePredictor", "XGBoost JSON doesn't contain tree structure, using fallback method")
		trees = emptyList()
	}

	private fun parseTree(t: JSONObject): Any {
		// Path 1: array-structured tree
		val leftChildrenArr = t.optJSONArray("left_children")
		val rightChildrenArr = t.optJSONArray("right_children")
		val splitIndicesArr = t.optJSONArray("split_indices")
		val splitCondsArr = t.optJSONArray("split_conditions")
		val defaultLeftArr = t.optJSONArray("default_left")
		val nodeTypesArr = t.optJSONArray("node_types")
		val leafValuesArr = t.optJSONArray("leaf_values")
		if (leftChildrenArr != null && rightChildrenArr != null && splitIndicesArr != null && splitCondsArr != null && defaultLeftArr != null && nodeTypesArr != null && leafValuesArr != null) {
			fun toIntArray(a: JSONArray) = IntArray(a.length()) { a.optInt(it, -1) }
			fun toFloatArray(a: JSONArray) = FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() }
			fun toBoolArray(a: JSONArray) = BooleanArray(a.length()) { a.optBoolean(it, true) }
			// Build mapping from node id to leaf index by scanning nodeTypes
			val nodeTypes = toIntArray(nodeTypesArr)
			val leafValues = toFloatArray(leafValuesArr)
			val leafIndexByNode = IntArray(nodeTypes.size) { -1 }
			var leafCounter = 0
			for (idx in nodeTypes.indices) {
				if (nodeTypes[idx] == 1) {
					leafIndexByNode[idx] = leafCounter
					leafCounter++
				}
			}
			return ArrayTree(
				leftChildren = toIntArray(leftChildrenArr),
				rightChildren = toIntArray(rightChildrenArr),
				splitIndices = toIntArray(splitIndicesArr),
				splitConditions = toFloatArray(splitCondsArr),
				defaultLeft = toBoolArray(defaultLeftArr),
				nodeTypes = nodeTypes,
				leafValues = leafValues,
				leafIndexByNode = leafIndexByNode
			)
		}

		// Path 2: node object list
		val nodesArray = t.optJSONArray("nodes") ?: JSONArray()
		val map = HashMap<Int, Any>(nodesArray.length())
		for (i in 0 until nodesArray.length()) {
			val nObj = nodesArray.getJSONObject(i)
			if (nObj.has("leaf")) {
				map[nObj.getInt("nodeid")] = LeafNode(nObj.getDouble("leaf").toFloat())
			} else {
				val splitFeature = when {
					nObj.has("split") -> nObj.getInt("split")
					nObj.has("split_feature") -> nObj.getInt("split_feature")
					nObj.has("split_index") -> nObj.getInt("split_index")
					else -> 0
				}
				val threshold = when {
					nObj.has("split_condition") -> nObj.getDouble("split_condition").toFloat()
					nObj.has("threshold") -> nObj.getDouble("threshold").toFloat()
					else -> 0f
				}
				val yesId = nObj.optInt("yes", nObj.optInt("left_child", 0))
				val noId = nObj.optInt("no", nObj.optInt("right_child", 0))
				val missingId = nObj.optInt("missing", yesId)
				map[nObj.getInt("nodeid")] = SplitNode(splitFeature, threshold, yesId, noId, missingId)
			}
		}
		return Tree(map)
	}

	private fun formatShelfLife(minutes: Int): String {
		Log.d("ShelfLifePredictor", "formatShelfLife input: $minutes minutes")
		val hours = if (minutes <= 0) 0 else minutes / 60
		val result = "$hours ${if (hours == 1) "hour" else "hours"} remaining"
		Log.d("ShelfLifePredictor", "formatShelfLife output: $result")
		return result
	}
}

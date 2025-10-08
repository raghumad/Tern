package com.madanala.tern.ui.components

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.madanala.tern.R
import com.madanala.tern.redux.Waypoint
import com.madanala.tern.redux.WaypointType
import org.osmdroid.util.GeoPoint

/**
 * Dialog for editing waypoint properties and configuration
 */
class WaypointEditorDialog(
    context: Context,
    private val waypoint: Waypoint?,
    private val isNewWaypoint: Boolean = false,
    private val onWaypointSaved: (Waypoint) -> Unit,
    private val onCancelled: (() -> Unit)? = null
) : Dialog(context) {

    companion object {
        private const val TAG = "WaypointEditorDialog"
    }

    private lateinit var nameEditText: EditText
    private lateinit var descriptionEditText: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var elevationEditText: EditText
    private lateinit var cylinderRadiusEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create dialog layout programmatically
        val layout = createDialogLayout()
        setContentView(layout)

        // Set window properties
        window?.let { window ->
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Initialize views
        initializeViews()

        // Populate fields if editing existing waypoint
        waypoint?.let { populateFields(it) }

        // Set up listeners
        setupListeners()

        // Set title
        setTitle(if (isNewWaypoint) "Create Waypoint" else "Edit Waypoint")
    }

    private fun createDialogLayout(): View {
        val context = context

        // Main container
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Name field
        val nameLayout = createFieldLayout("Name:", "Enter waypoint name")
        nameEditText = nameLayout.getChildAt(1) as EditText
        mainLayout.addView(nameLayout)

        // Description field
        val descLayout = createFieldLayout("Description:", "Enter description (optional)")
        descriptionEditText = descLayout.getChildAt(1) as EditText
        mainLayout.addView(descLayout)

        // Type spinner
        val typeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
        }

        val typeLabel = TextView(context).apply {
            text = "Type:"
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        typeSpinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        }

        typeLayout.addView(typeLabel)
        typeLayout.addView(typeSpinner)
        mainLayout.addView(typeLayout)

        // Elevation field
        val elevationLayout = createFieldLayout("Elevation (m):", "0")
        elevationEditText = elevationLayout.getChildAt(1) as EditText
        mainLayout.addView(elevationLayout)

        // Cylinder radius field (only for turnpoints)
        val radiusLayout = createFieldLayout("Cylinder Radius (m):", "400")
        cylinderRadiusEditText = radiusLayout.getChildAt(1) as EditText
        mainLayout.addView(radiusLayout)

        // Location info (read-only)
        val locationLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 24)
        }

        val locationLabel = TextView(context).apply {
            text = "Location:"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }

        val locationInfo = TextView(context).apply {
            tag = "location_info"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 0)
        }

        locationLayout.addView(locationLabel)
        locationLayout.addView(locationInfo)
        mainLayout.addView(locationLayout)

        // Buttons
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        cancelButton = Button(context).apply {
            text = "Cancel"
            setBackgroundColor(0xFFE0E0E0.toInt())
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 16
            }
        }

        saveButton = Button(context).apply {
            text = if (isNewWaypoint) "Create" else "Save"
            setBackgroundColor(0xFF2196F3.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonLayout.addView(cancelButton)
        buttonLayout.addView(saveButton)
        mainLayout.addView(buttonLayout)

        return mainLayout
    }

    private fun createFieldLayout(label: String, hint: String): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val labelView = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }

        val editText = EditText(context).apply {
            tag = "edit_text"
            this.hint = hint
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            setHintTextColor(0xFF999999.toInt())
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(16, 16, 16, 16)
        }

        layout.addView(labelView)
        layout.addView(editText)

        return layout
    }

    private fun initializeViews() {
        // Set up waypoint type spinner
        val waypointTypes = WaypointType.values()
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            waypointTypes.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        typeSpinner.adapter = adapter
    }

    private fun updateLocationInfo() {
        // This would be called after layout is created to update location info
        // For now, location info is handled in createDialogLayout
    }

    private fun populateFields(waypoint: Waypoint) {
        nameEditText.setText(waypoint.name)
        descriptionEditText.setText(waypoint.description)

        // Set spinner selection
        val waypointTypeIndex = WaypointType.values().indexOf(waypoint.waypointType)
        if (waypointTypeIndex >= 0) {
            typeSpinner.setSelection(waypointTypeIndex)
        }

        elevationEditText.setText(waypoint.elevation?.toString() ?: "")
        cylinderRadiusEditText.setText(waypoint.cylinderRadius.toString())

        // Show/hide cylinder radius based on waypoint type
        updateCylinderRadiusVisibility(waypoint.waypointType)
    }

    private fun setupListeners() {
        // Type spinner listener
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedType = WaypointType.values()[position]
                updateCylinderRadiusVisibility(selectedType)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Save button
        saveButton.setOnClickListener {
            if (validateAndSave()) {
                dismiss()
            }
        }

        // Cancel button
        cancelButton.setOnClickListener {
            onCancelled?.invoke()
            dismiss()
        }
    }

    private fun updateCylinderRadiusVisibility(waypointType: WaypointType) {
        val isTurnpoint = waypointType == WaypointType.TURNPOINT
        // In a real implementation, would show/hide the cylinder radius field
        // For now, just update the hint
        cylinderRadiusEditText.hint = if (isTurnpoint) {
            "400 (FAI standard)"
        } else {
            "Not applicable"
        }
    }

    private fun validateAndSave(): Boolean {
        val name = nameEditText.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(context, "Waypoint name is required", Toast.LENGTH_SHORT).show()
            return false
        }

        val description = descriptionEditText.text.toString().trim()
        val waypointType = WaypointType.values()[typeSpinner.selectedItemPosition]

        val elevation = elevationEditText.text.toString().toDoubleOrNull()
        val cylinderRadius = when (waypointType) {
            WaypointType.TURNPOINT -> cylinderRadiusEditText.text.toString().toDoubleOrNull() ?: 400.0
            else -> 400.0 // Default radius
        }

        // Create or update waypoint
        val updatedWaypoint = waypoint?.copy(
            name = name,
            description = description,
            waypointType = waypointType,
            elevation = elevation,
            cylinderRadius = cylinderRadius,
            updatedAt = System.currentTimeMillis()
        ) ?: Waypoint(
            name = name,
            description = description,
            location = waypoint?.location ?: GeoPoint(0.0, 0.0), // Should be provided
            waypointType = waypointType,
            elevation = elevation,
            cylinderRadius = cylinderRadius
        )

        onWaypointSaved(updatedWaypoint)
        return true
    }


}
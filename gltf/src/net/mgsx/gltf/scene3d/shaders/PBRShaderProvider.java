package net.mgsx.gltf.scene3d.shaders;

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.utils.GdxRuntimeException;

import net.mgsx.gltf.scene3d.attributes.FogAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRFlagAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRVertexAttributes;
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig.SRGB;
import net.mgsx.gltf.scene3d.utils.LightUtils;
import net.mgsx.gltf.scene3d.utils.LightUtils.LightsInfo;

public class PBRShaderProvider extends DefaultShaderProvider {
	public static final String TAG = "PBRShader";

	private static final LightsInfo lightsInfo = new LightsInfo();

	public static PBRShaderConfig createDefaultConfig() {
		PBRShaderConfig config = new PBRShaderConfig();
		config.vertexShader = Gdx.files.classpath("net/mgsx/gltf/shaders/gdx-pbr.vs.glsl").readString();
		config.fragmentShader = Gdx.files.classpath("net/mgsx/gltf/shaders/gdx-pbr.fs.glsl").readString();
		return config;
	};

	public static DepthShader.Config createDefaultDepthConfig() {
		DepthShader.Config config = new DepthShader.Config();
		config.vertexShader = Gdx.files.classpath("net/mgsx/gltf/shaders/depth.vs.glsl").readString();
		config.fragmentShader = Gdx.files.classpath("net/mgsx/gltf/shaders/depth.fs.glsl").readString();
		return config;
	};

	public static PBRShaderProvider createDefault(int maxBones) {
		PBRShaderConfig config = createDefaultConfig();
		config.numBones = maxBones;
		return createDefault(config);
	}

	public static PBRShaderProvider createDefault(PBRShaderConfig config) {
		return new PBRShaderProvider(config);
	}

	public static DepthShaderProvider createDefaultDepth(int maxBones) {
		DepthShader.Config config = createDefaultDepthConfig();
		config.numBones = maxBones;
		return createDefaultDepth(config);
	}

	public static DepthShaderProvider createDefaultDepth(DepthShader.Config config) {
		return new PBRDepthShaderProvider(config);
	}

	public PBRShaderProvider(PBRShaderConfig config) {
		super(config);
	}

	public int getShaderCount() {
		return shaders.size;
	}

	public static String morphTargetsPrefix(Renderable renderable) {
		String prefix = "";
		// TODO optimize double loop
		for (VertexAttribute att : renderable.meshPart.mesh.getVertexAttributes()) {
			for (int i = 0; i < PBRCommon.MAX_MORPH_TARGETS; i++) {
				if (att.usage == PBRVertexAttributes.Usage.PositionTarget && att.unit == i) {
					prefix += "#define " + "position" + i + "Flag\n";
				} else if (att.usage == PBRVertexAttributes.Usage.NormalTarget && att.unit == i) {
					prefix += "#define " + "normal" + i + "Flag\n";
				} else if (att.usage == PBRVertexAttributes.Usage.TangentTarget && att.unit == i) {
					prefix += "#define " + "tangent" + i + "Flag\n";
				}
			}
		}
		return prefix;
	}

	protected Shader createShader(Renderable renderable) {

		PBRShaderConfig config = (PBRShaderConfig) this.config;

		String prefix = DefaultShader.createPrefix(renderable, config);
		String version = config.glslVersion;
		final boolean openGL3 = Gdx.graphics.getGLVersion().isVersionEqualToOrHigher(3, 0);
		if (openGL3) {
			if (Gdx.app.getType() == ApplicationType.Desktop) {
				if (version == null)
					version = "#version 130\n" + "#define GLSL3\n";
			} else if (Gdx.app.getType() == ApplicationType.Android) {
				if (version == null)
					version = "#version 300 es\n" + "#define GLSL3\n";
			}
		}
		if (version != null) {
			prefix = version + prefix;
		}

		if (Gdx.app.getType() == ApplicationType.WebGL || !openGL3) {
			// extension required to auto compute tangents
			if (renderable.meshPart.mesh.getVertexAttribute(VertexAttributes.Usage.Tangent) == null
					&& config.useTangentSpace) {
				// not that WebGL need that call to allow extension to be enabled in shaders.
				if (Gdx.graphics.supportsExtension("GL_OES_standard_derivatives")) {
					prefix += "#define USE_DERIVATIVES_EXT\n";
				} else {
					throw new GdxRuntimeException(
							"GL_OES_standard_derivatives extension or tangent vertex attribute required");
				}
			}
		}

		// Morph targets

		prefix += morphTargetsPrefix(renderable);

		// Lighting

		if (renderable.material.has(PBRFlagAttribute.Unlit)) {

			prefix += "#define unlitFlag\n";

		} else {

			if (renderable.material.has(PBRTextureAttribute.MetallicRoughnessTexture)) {
				prefix += "#define metallicRoughnessTextureFlag\n";
			}
			if (renderable.material.has(PBRTextureAttribute.OcclusionTexture)) {
				prefix += "#define occlusionTextureFlag\n";
			}

			// IBL options
			PBRCubemapAttribute specualarCubemapAttribute = null;
			if (renderable.environment != null) {
				if (renderable.environment.has(PBRCubemapAttribute.SpecularEnv)) {
					prefix += "#define diffuseSpecularEnvSeparateFlag\n";
					specualarCubemapAttribute = renderable.environment.get(PBRCubemapAttribute.class,
							PBRCubemapAttribute.SpecularEnv);
				} else if (renderable.environment.has(PBRCubemapAttribute.DiffuseEnv)) {
					specualarCubemapAttribute = renderable.environment.get(PBRCubemapAttribute.class,
							PBRCubemapAttribute.DiffuseEnv);
				} else if (renderable.environment.has(PBRCubemapAttribute.EnvironmentMap)) {
					specualarCubemapAttribute = renderable.environment.get(PBRCubemapAttribute.class,
							PBRCubemapAttribute.EnvironmentMap);
				}
				if (specualarCubemapAttribute != null) {
					prefix += "#define USE_IBL\n";

					boolean textureLodSupported;
					if (openGL3) {
						textureLodSupported = true;
					} else if (Gdx.graphics.supportsExtension("EXT_shader_texture_lod")) {
						prefix += "#define USE_TEXTURE_LOD_EXT\n";
						textureLodSupported = true;
					} else {
						textureLodSupported = false;
					}

					TextureFilter textureFilter = specualarCubemapAttribute.textureDescription.minFilter != null
							? specualarCubemapAttribute.textureDescription.minFilter
							: specualarCubemapAttribute.textureDescription.texture.getMinFilter();
					if (textureLodSupported && textureFilter.equals(TextureFilter.MipMap)) {
						prefix += "#define USE_TEX_LOD\n";
					}

					if (renderable.environment.has(PBRTextureAttribute.BRDFLUTTexture)) {
						prefix += "#define brdfLUTTexture\n";
					}
				}
				// TODO check GLSL extension 'OES_standard_derivatives' for WebGL
				// TODO check GLSL extension 'EXT_SRGB' for WebGL

				if (renderable.environment.has(ColorAttribute.AmbientLight)) {
					prefix += "#define ambientLightFlag\n";
				}
			}

			// SRGB
			if (config.manualSRGB != SRGB.NONE) {
				prefix += "#define MANUAL_SRGB\n";
				if (config.manualSRGB == SRGB.FAST) {
					prefix += "#define SRGB_FAST_APPROXIMATION\n";
				}
			}
		}

		// multi UVs
		int maxUVIndex = 0;

		{
			TextureAttribute attribute = renderable.material.get(TextureAttribute.class, TextureAttribute.Diffuse);
			if (attribute != null) {
				prefix += "#define v_diffuseUV v_texCoord" + attribute.uvIndex + "\n";
				maxUVIndex = Math.max(maxUVIndex, attribute.uvIndex);
			}
		}
		{
			TextureAttribute attribute = renderable.material.get(TextureAttribute.class, TextureAttribute.Emissive);
			if (attribute != null) {
				prefix += "#define v_emissiveUV v_texCoord" + attribute.uvIndex + "\n";
				maxUVIndex = Math.max(maxUVIndex, attribute.uvIndex);
			}
		}
		{
			TextureAttribute attribute = renderable.material.get(TextureAttribute.class, TextureAttribute.Normal);
			if (attribute != null) {
				prefix += "#define v_normalUV v_texCoord" + attribute.uvIndex + "\n";
				maxUVIndex = Math.max(maxUVIndex, attribute.uvIndex);
			}
		}
		{
			TextureAttribute attribute = renderable.material.get(TextureAttribute.class,
					PBRTextureAttribute.MetallicRoughnessTexture);
			if (attribute != null) {
				prefix += "#define v_metallicRoughnessUV v_texCoord" + attribute.uvIndex + "\n";
				maxUVIndex = Math.max(maxUVIndex, attribute.uvIndex);
			}
		}
		{
			TextureAttribute attribute = renderable.material.get(TextureAttribute.class,
					PBRTextureAttribute.OcclusionTexture);
			if (attribute != null) {
				prefix += "#define v_occlusionUV v_texCoord" + attribute.uvIndex + "\n";
				maxUVIndex = Math.max(maxUVIndex, attribute.uvIndex);
			}
		}

		if (maxUVIndex >= 0) {
			prefix += "#define textureFlag\n";
		}
		if (maxUVIndex == 1) {
			prefix += "#define textureCoord1Flag\n";
		} else if (maxUVIndex > 1) {
			throw new GdxRuntimeException("more than 2 texture coordinates attribute not supported");
		}

		// Fog

		if (renderable.environment.has(FogAttribute.FogEquation)) {
			prefix += "#define fogEquationFlag\n";
		}

		// colors
		for (VertexAttribute attribute : renderable.meshPart.mesh.getVertexAttributes()) {
			if (attribute.usage == VertexAttributes.Usage.ColorUnpacked) {
				prefix += "#define color" + attribute.unit + "Flag\n";
			}
		}

		//

		int numBoneInfluence = 0;
		int numMorphTarget = 0;
		int numColor = 0;

		for (VertexAttribute attribute : renderable.meshPart.mesh.getVertexAttributes()) {
			if (attribute.usage == VertexAttributes.Usage.ColorPacked) {
				throw new GdxRuntimeException("color packed attribute not supported");
			} else if (attribute.usage == VertexAttributes.Usage.ColorUnpacked) {
				numColor = Math.max(numColor, attribute.unit + 1);
			} else if (attribute.usage == PBRVertexAttributes.Usage.PositionTarget
					&& attribute.unit >= PBRCommon.MAX_MORPH_TARGETS
					|| attribute.usage == PBRVertexAttributes.Usage.NormalTarget
							&& attribute.unit >= PBRCommon.MAX_MORPH_TARGETS
					|| attribute.usage == PBRVertexAttributes.Usage.TangentTarget
							&& attribute.unit >= PBRCommon.MAX_MORPH_TARGETS) {
				numMorphTarget = Math.max(numMorphTarget, attribute.unit + 1);
			} else if (attribute.usage == VertexAttributes.Usage.BoneWeight) {
				numBoneInfluence = Math.max(numBoneInfluence, attribute.unit + 1);
			}
		}

		PBRCommon.checkVertexAttributes(renderable);

		if (numBoneInfluence > 8) {
			Gdx.app.error(TAG, "more than 8 bones influence attributes not supported: " + numBoneInfluence + " found.");
		}
		if (numMorphTarget > PBRCommon.MAX_MORPH_TARGETS) {
			Gdx.app.error(TAG, "more than 8 morph target attributes not supported: " + numMorphTarget + " found.");
		}
		if (numColor > config.numVertexColors) {
			Gdx.app.error(TAG,
					"more than " + config.numVertexColors + " color attributes not supported: " + numColor + " found.");
		}

		LightUtils.getLightsInfo(lightsInfo, renderable.environment);
		if (lightsInfo.dirLights > config.numDirectionalLights) {
			Gdx.app.error(TAG, "too many directional lights detected: " + lightsInfo.dirLights + "/"
					+ config.numDirectionalLights);
		}
		if (lightsInfo.pointLights > config.numPointLights) {
			Gdx.app.error(TAG,
					"too many point lights detected: " + lightsInfo.pointLights + "/" + config.numPointLights);
		}
		if (lightsInfo.spotLights > config.numSpotLights) {
			Gdx.app.error(TAG, "too many spot lights detected: " + lightsInfo.spotLights + "/" + config.numSpotLights);
		}
		if (lightsInfo.miscLights > 0) {
			Gdx.app.error(TAG, "unknow type lights not supported.");
		}

		PBRShader shader = new PBRShader(renderable, config, prefix);
		String shaderLog = shader.program.getLog();
		if (shader.program.isCompiled()) {
			if (shaderLog.isEmpty()) {
				Gdx.app.log(TAG, "Shader compilation success");
			} else {
				Gdx.app.error(TAG, "Shader compilation warnings:\n" + shaderLog);
			}
		} else {
			throw new GdxRuntimeException("Shader compilation failed:\n" + shaderLog);
		}

		// prevent infinite loop
		if (!shader.canRender(renderable)) {
			throw new GdxRuntimeException("cannot render with this shader");
		}

		return shader;
	};
}

/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011-2012, Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine.mesh;

import gnu.trove.list.array.TFloatArrayList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.spout.api.Spout;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.cuboid.ChunkSnapshot;
import org.spout.api.geo.cuboid.ChunkSnapshotModel;
import org.spout.api.geo.discrete.Point;
import org.spout.api.material.BlockMaterial;
import org.spout.api.material.Material;
import org.spout.api.material.block.BlockFace;
import org.spout.api.math.Vector3;
import org.spout.api.model.mesh.MeshFace;
import org.spout.api.model.mesh.OrientedMesh;
import org.spout.api.model.mesh.OrientedMeshFace;
import org.spout.api.model.mesh.Vertex;
import org.spout.api.render.BufferContainer;
import org.spout.api.render.RenderMaterial;
import org.spout.api.render.effect.BufferEffect;
import org.spout.api.render.effect.SnapshotMesh;
import org.spout.api.util.bytebit.ByteBitSet;
import org.spout.engine.renderer.BatchVertexRenderer;
import org.spout.engine.world.SpoutChunkSnapshotModel;

/**
 * Represents a mesh for a chunk.
 */
public class ChunkMesh{

	private HashMap<RenderMaterial, BufferContainer> meshs = new HashMap<RenderMaterial, BufferContainer>();

	private SpoutChunkSnapshotModel chunkModel;
	private ChunkSnapshot center;
	private final World world;
	private final int chunkX,chunkY,chunkZ;
	private boolean isUnloaded = false;
	private boolean first = false;

	/**
	 * Time of the used SpoutChunkSnapshotModel generation
	 * To benchmark purpose
	 */
	private final long time;

	public ChunkMesh(SpoutChunkSnapshotModel chunkModel) {
		this.chunkModel = chunkModel;
		first = chunkModel.isFirst();

		world = chunkModel.getWorld();

		chunkX = chunkModel.getX();
		chunkY = chunkModel.getY();
		chunkZ = chunkModel.getZ();

		time = chunkModel.getTime();
	}

	public int getChunkX(){
		return chunkX;
	}

	public int getChunkY(){
		return chunkY;
	}

	public int getChunkZ(){
		return chunkZ;
	}

	public void update(){
		if(chunkModel.isUnload()){
			isUnloaded = true;
			return;
		}

		center = chunkModel.getCenter();

		//Update mesh vertex and light

		updateBlock();

		//Execute post buffer effect for each renderMaterial
		for(Entry<RenderMaterial, BufferContainer> entry : meshs.entrySet()){
			for(BufferEffect effect : entry.getKey().getBufferEffects()){
				effect.post(chunkModel, entry.getValue());
			}
		}

		// Free memory
		chunkModel = null;
		center = null;
	}

	/**
	 * Updates the mesh.
	 */
	private void updateBlock() {
		for (int x = center.getBase().getBlockX(); x < center.getBase().getBlockX() + Chunk.BLOCKS.SIZE; x++) {
			for (int y = center.getBase().getBlockY(); y < center.getBase().getBlockY() + Chunk.BLOCKS.SIZE; y++) {
				for (int z = center.getBase().getBlockZ(); z < center.getBase().getBlockZ() + Chunk.BLOCKS.SIZE; z++) {
					generateBlockVertices(chunkModel,x, y, z);
				}
			}
		}
	}

	public List<MeshFace> buildBlock(ChunkSnapshotModel chunkSnapshotModel,Material blockMaterial,
			Vector3 position, boolean toRender[], OrientedMesh mesh) {
		List<MeshFace> meshs = new ArrayList<MeshFace>();
		Vector3 model = new Vector3(position.getX(), position.getY(), position.getZ());
		for(OrientedMeshFace meshFace : mesh){

			if(!meshFace.canRender(toRender))
				continue;

			Iterator<Vertex> it = meshFace.iterator();
			Vertex v1 = new Vertex(it.next());
			Vertex v2 = new Vertex(it.next());
			Vertex v3 = new Vertex(it.next());
			v1.position = v1.position.add(model);
			v2.position = v2.position.add(model);
			v3.position = v3.position.add(model);

			meshs.add(new MeshFace(v1, v2, v3));
		}
		return meshs;
	}

	/**
	 * Generates the vertices of the given block and adds them to the ChunkMesh.
	 * @param chunkSnapshotModel 
	 * 
	 * @param x
	 * @param y
	 * @param z
	 */
	private void generateBlockVertices(SpoutChunkSnapshotModel chunkSnapshotModel, int x, int y, int z) {
		BlockMaterial material = center.getBlockMaterial(x, y, z);

		if (material.isInvisible()) {
			return;
		}

		RenderMaterial renderMaterial = material.getModel().getRenderMaterial();

		if( !chunkModel.hasRenderMaterial(renderMaterial) ){
			return;
		}

		Vector3 position = new Vector3(x, y, z);

		boolean toRender[] = new boolean[OrientedMeshFace.shouldRender.length];
		boolean fullyOccluded = true;
		for(int i = 0; i < OrientedMeshFace.shouldRender.length; i++){
			BlockFace face = OrientedMeshFace.shouldRender[i];
			Vector3 facePos = position.add(face.getOffset());
			int x1 = facePos.getFloorX();
			int y1 = facePos.getFloorY();
			int z1 = facePos.getFloorZ();

			BlockMaterial neighbor = chunkModel.getChunkFromBlock(x1, y1, z1).getBlockMaterial(x1, y1, z1);

			if (material.isFaceRendered(face, neighbor)) {
				toRender[i] = true;
				fullyOccluded = false;
			}else{
				toRender[i] = false;
				continue;
			}

			ByteBitSet occlusion = neighbor.getOcclusion(material.getData());

			if (occlusion.get(face.getOpposite())) {
				toRender[i] = false;
				continue;
			}else{
				toRender[i] = true;
				fullyOccluded = false;
			}
		}

		if(fullyOccluded)
			return;

		SnapshotMesh snapshotMesh = new SnapshotMesh(material, chunkSnapshotModel, new Point(position, world), toRender);

		renderMaterial.preMesh(snapshotMesh);
		List<MeshFace> faces = buildBlock(snapshotMesh.getSnapshotModel(), snapshotMesh.getMaterial(),
				snapshotMesh.getPosition(), snapshotMesh.getToRender(), (OrientedMesh)snapshotMesh.getMesh());
		snapshotMesh.setResult(faces);
		renderMaterial.postMesh(snapshotMesh);
		faces = snapshotMesh.getResult();

		if(!faces.isEmpty()){
			BufferContainer container = meshs.get(renderMaterial);

			if(container == null){
				container = new BufferContainer();
				meshs.put(renderMaterial, container);
			}

			TFloatArrayList vertexBuffer = (TFloatArrayList) container.getBuffers().get(BatchVertexRenderer.VERTEX_LAYER);
			TFloatArrayList normalBuffer = (TFloatArrayList) container.getBuffers().get(BatchVertexRenderer.NORMAL_LAYER);
			TFloatArrayList textureBuffer = (TFloatArrayList) container.getBuffers().get(BatchVertexRenderer.TEXTURE0_LAYER);

			if(vertexBuffer==null){
				vertexBuffer = new TFloatArrayList();
				container.setBuffers(BatchVertexRenderer.VERTEX_LAYER, vertexBuffer);
			}

			if(normalBuffer==null){
				normalBuffer = new TFloatArrayList();
				container.setBuffers(BatchVertexRenderer.NORMAL_LAYER, normalBuffer);
			}

			if(textureBuffer==null){
				textureBuffer = new TFloatArrayList();
				container.setBuffers(BatchVertexRenderer.TEXTURE0_LAYER, textureBuffer);
			}

			for (MeshFace meshFace : faces) {
				for (Vertex vert : meshFace) {

					vertexBuffer.add(vert.position.getX());
					vertexBuffer.add(vert.position.getY());
					vertexBuffer.add(vert.position.getZ());
					vertexBuffer.add(1f);

					if(vert.texCoord0 != null){
						textureBuffer.add(vert.texCoord0.getX());
						textureBuffer.add(vert.texCoord0.getY());
					}

					if(vert.normal != null){
						normalBuffer.add(vert.normal.getX());
						normalBuffer.add(vert.normal.getY());
						normalBuffer.add(vert.normal.getZ());
						normalBuffer.add(0f);
					}

					container.element++;
				}
			}
		}
	}

	/**
	 * Checks if the chunk mesh has any vertices.
	 * 
	 * @return
	 */
	public boolean hasVertices() {
		return !meshs.isEmpty();
	}

	@Override
	public String toString() {
		return "ChunkMesh [center=" + center + "]";
	}

	public boolean isUnloaded() {
		return isUnloaded;
	}

	public Map<RenderMaterial, BufferContainer> getMaterialsFaces() {
		return meshs;
	}

	public boolean isFirst() {
		return first;
	}

	public long getTime() {
		return time;
	}

	public World getWorld() {
		return world;
	}

}

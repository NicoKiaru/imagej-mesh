package net.imagej.mesh.ply;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.mastodon.collection.RefList;
import org.mastodon.collection.ref.RefArrayList;
import org.scijava.plugin.AbstractHandlerPlugin;
import org.smurn.jply.Element;
import org.smurn.jply.ElementReader;
import org.smurn.jply.PlyReader;
import org.smurn.jply.PlyReaderFile;
import org.smurn.jply.util.NormalMode;
import org.smurn.jply.util.NormalizingPlyReader;
import org.smurn.jply.util.TesselationMode;
import org.smurn.jply.util.TextureMode;

import gnu.trove.map.hash.TIntIntHashMap;
import net.imagej.mesh.DefaultMesh;
import net.imagej.mesh.Mesh;
import net.imagej.mesh.Triangle;
import net.imagej.mesh.TrianglePool;
import net.imagej.mesh.Vertex3;
import net.imagej.mesh.Vertex3Pool;



/**
 * A class for reading and writing PLY files
 * 
 * PLY specs: http://www.cs.virginia.edu/~gfx/Courses/2001/Advanced.spring.01/plylib/Ply.txt
 *
 * @author Kyle Harrington (University of Idaho, Moscow)
 */
public class DefaultPLYFormat extends AbstractHandlerPlugin<File>
	implements PLYFormat {

	@Override
	public Class<File> getType() {
		return File.class;
	}

	@Override
	public Mesh read(File plyFile) throws IOException {
		PlyReader plyReader = new PlyReaderFile(plyFile);
		plyReader = new NormalizingPlyReader(plyReader,
                TesselationMode.TRIANGLES,
                NormalMode.ADD_NORMALS_CCW,
                TextureMode.PASS_THROUGH);
		
		int vertexCount = plyReader.getElementCount("vertex");
		int triangleCount = plyReader.getElementCount("face");
				
		Mesh mesh = new DefaultMesh(vertexCount,triangleCount);
		Vertex3Pool vp = mesh.getVertex3Pool();
		TrianglePool tp = mesh.getTrianglePool();
		
		ElementReader reader = plyReader.nextElementReader();
        
        // First read vertices so we have all indices, maybe this could be more efficient
        final RefList<Vertex3> vertices = new RefArrayList<>( vp ); 
        while (reader != null) {        	
            if (reader.getElementType().getName().equals("vertex")) {
            	Element vertex = reader.readElement();
            	while (vertex != null )
        		{
        			final Vertex3 v = vp.create().init( (float)vertex.getDouble("x"), (float)vertex.getDouble("y"), (float)vertex.getDouble("z"), 
        					(float)vertex.getDouble("nx"), (float)vertex.getDouble("ny"), (float)vertex.getDouble("nz"), 
        					0f, 0f, 0f );// We could populate our texture coordinate here
        			vertices.add( v );
        			vertex = reader.readElement();
        		}
            } 
            reader.close();
            reader = plyReader.nextElementReader();
        }
        plyReader.close();
        
        // Now read faces
        plyReader = new PlyReaderFile(plyFile);
		plyReader = new NormalizingPlyReader(plyReader,
                TesselationMode.TRIANGLES,
                NormalMode.ADD_NORMALS_CCW,
                TextureMode.PASS_THROUGH);
		
        reader = plyReader.nextElementReader();
        
        // First read vertices so we have all indices, maybe this could be more efficient
        final RefList<Triangle> triangles = new RefArrayList<>( tp ); 
        while (reader != null) {        	
            if (reader.getElementType().getName().equals("face")) {
            	Element triangle = reader.readElement();
                while (triangle != null) {
                    int[] indices = triangle.getIntList("vertex_index");
                    final Vertex3 v1 = vertices.get( indices[0] );
        			final Vertex3 v2 = vertices.get( indices[1] );
        			final Vertex3 v3 = vertices.get( indices[2] );
        			final Triangle t = tp.create().init( v1, v2, v3, 
        					vp.create().init( 0f, 0f, 0f, 
        							0f, 0f, 0f,
        							0f, 0f, 0f ) );
        			triangles.add( t );
                    triangle = reader.readElement();
                }
            }
            reader.close();
            reader = plyReader.nextElementReader();
        }
		
        plyReader.close();
		
        mesh.setTriangles(triangles);
        mesh.setVertices(vertices);
        
		return mesh;
	}

	@Override
	public byte[] writeBinary(Mesh mesh) throws IOException {
		final int vertexBytes = 3 * 4 + 3 * 4 + 3 * 4;
		final int triangleBytes = 3 * 4 + 1;
		final String header = "ply\nformat binary_little_endian 1.0\ncomment This binary PLY mesh was created with imagej-mesh.\n";
		final String vertexHeader = "element vertex " + mesh.getVertices().size() + "\nproperty float x\nproperty float y\nproperty float z\nproperty float nx\nproperty float ny\nproperty float nz\nproperty float r\n property float g\n property float b\n";
		final String triangleHeader = "element face " + mesh.getTriangles().size() + "\nproperty list uchar int vertex_index\n";
		final String endHeader = "end_header\n";
		final int bytes = header.getBytes().length + vertexHeader.getBytes().length + triangleHeader.getBytes().length + endHeader.getBytes().length + mesh.getVertices().size() * vertexBytes + mesh.getTriangles().size() * triangleBytes;
		final ByteBuffer buffer = ByteBuffer.allocate(bytes).order(
				ByteOrder.LITTLE_ENDIAN);
		
		buffer.put(header.getBytes());
		buffer.put(vertexHeader.getBytes());
		buffer.put(triangleHeader.getBytes());
		buffer.put(endHeader.getBytes());

		// Do not populate file if there are no vertices
		if (mesh.getVertices().isEmpty()) {
			return buffer.array();
		}

		// Write vertices
		TIntIntHashMap refToVertId = new TIntIntHashMap( mesh.getVertices().size() );
		Vertex3Pool vp = mesh.getVertex3Pool();
		int vertId = 0;
		for( Vertex3 v : mesh.getVertices() ) {
			buffer.putFloat((float) v.getX());
			buffer.putFloat((float) v.getY());
			buffer.putFloat((float) v.getZ());
			buffer.putFloat((float) v.getNX());
			buffer.putFloat((float) v.getNY());
			buffer.putFloat((float) v.getNZ());
			buffer.putFloat((float) v.getU());
			buffer.putFloat((float) v.getV());
			buffer.putFloat((float) v.getW());
			refToVertId.put( vp.getId(v), vertId);
			++vertId;
		}
		
		// Write triangles
		for( Triangle t : mesh.getTriangles() ) {
			buffer.put((byte) 3);
			buffer.putInt(refToVertId.get(t.getVertex(0).getInternalPoolIndex()));
			buffer.putInt(refToVertId.get(t.getVertex(1).getInternalPoolIndex()));
			buffer.putInt(refToVertId.get(t.getVertex(2).getInternalPoolIndex()));
		}
		
		return buffer.array();
	}

	@Override
	public byte[] writeAscii(Mesh mesh) throws IOException {
		final int vertexBytes = 3 * 4 + 3 * 4 + 3 * 4;
		final int triangleBytes = 3 * 4 + 1;
		final String header = "ply\nformat ascii 1.0\ncomment This binary PLY mesh was created with imagej-mesh.\n";
		final String vertexHeader = "element vertex " + mesh.getVertices().size() + "\nproperty float x\nproperty float y\nproperty float z\nproperty float nx\nproperty float ny\nproperty float nz\nproperty float r\n property float g\n property float b\n";
		final String triangleHeader = "element face " + mesh.getTriangles().size() + "\nproperty list uchar int vertex_index\n";
		final String endHeader = "end_header\n";
		
		ByteArrayOutputStream os=new ByteArrayOutputStream();
		
		Writer writer = new OutputStreamWriter(os, "UTF-8");
		
		writer.write(header+vertexHeader+triangleHeader+endHeader);		
		writer.flush();
		
		// Do not populate file if there are no vertices
		if (mesh.getVertices().isEmpty()) {			
			return os.toByteArray();
		}

		// Write vertices
		TIntIntHashMap refToVertId = new TIntIntHashMap( mesh.getVertices().size() );
		Vertex3Pool vp = mesh.getVertex3Pool();
		int vertId = 0;
		for( Vertex3 v : mesh.getVertices() ) {
			writer.write(Float.toString((float) v.getX()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getY()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getZ()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getNX()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getNY()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getNZ()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getU()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getV()));
			writer.write(' ');
			writer.write(Float.toString((float) v.getW()));
			writer.write('\n');
			refToVertId.put( vp.getId(v), vertId);
			++vertId;
		}
		
		// Write triangles
		for( Triangle t : mesh.getTriangles() ) {
			writer.write("3 ");
			writer.write(Integer.toString(refToVertId.get(t.getVertex(0).getInternalPoolIndex())));
			writer.write(' ');
			writer.write(Integer.toString(refToVertId.get(t.getVertex(1).getInternalPoolIndex())));
			writer.write(' ');
			writer.write(Integer.toString(refToVertId.get(t.getVertex(2).getInternalPoolIndex())));
			writer.write('\n');
		}
		writer.flush();
		return os.toByteArray();
	}
	
}
